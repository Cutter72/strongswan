/* Support of OpenPGP certificates
 * Copyright (C) 2002-2009 Andreas Steffen
 *
 * HSR - Hochschule fuer Technik Rapperswil
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.  See <http://www.fsf.org/copyleft/gpl.txt>.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 */

#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <freeswan.h>

#include <library.h>
#include <enum.h>
#include <crypto/hashers/hasher.h>

#include "constants.h"
#include "defs.h"
#include "log.h"
#include "id.h"
#include "pgpcert.h"
#include "certs.h"
#include "whack.h"
#include "keys.h"


typedef enum pgp_packet_tag_t pgp_packet_tag_t;

/**
 * OpenPGP packet tags as defined in section 4.3 of RFC 4880
 */
enum pgp_packet_tag_t {
	PGP_PKT_RESERVED               =  0,
	PGP_PKT_PUBKEY_ENC_SESSION_KEY =  1,
	PGP_PKT_SIGNATURE              =  2,
	PGP_PKT_SYMKEY_ENC_SESSION_KEY =  3,
	PGP_PKT_ONE_PASS_SIGNATURE_PKT =  4,
	PGP_PKT_SECRET_KEY             =  5,
	PGP_PKT_PUBLIC_KEY             =  6,
	PGP_PKT_SECRET_SUBKEY          =  7,
	PGP_PKT_COMPRESSED_DATA        =  8,
	PGP_PKT_SYMKEY_ENC_DATA        =  9,
	PGP_PKT_MARKER                 = 10,
	PGP_PKT_LITERAL_DATA           = 11,
	PGP_PKT_TRUST                  = 12,
	PGP_PKT_USER_ID                = 13,
	PGP_PKT_PUBLIC_SUBKEY          = 14,
	PGP_PKT_USER_ATTRIBUTE         = 17,
	PGP_PKT_SYM_ENC_INT_PROT_DATA  = 18,
	PGP_PKT_MOD_DETECT_CODE        = 19
};

ENUM_BEGIN(pgp_packet_tag_names, PGP_PKT_RESERVED, PGP_PKT_PUBLIC_SUBKEY,
	"Reserved",
	"Public-Key Encrypted Session Key Packet",
	"Signature Packet",
	"Symmetric-Key Encrypted Session Key Packet",
	"One-Pass Signature Packet",
	"Secret Key Packet",
	"Public Key Packet",
	"Secret Subkey Packet",
	"Compressed Data Packet",
	"Symmetrically Encrypted Data Packet",
	"Marker Packet",
	"Literal Data Packet",
	"Trust Packet",
	"User ID Packet",
	"Public Subkey Packet"
);
ENUM_NEXT(pgp_packet_tag_names, PGP_PKT_USER_ATTRIBUTE, PGP_PKT_MOD_DETECT_CODE, PGP_PKT_PUBLIC_SUBKEY,
	"User Attribute Packet",
	"Sym. Encrypted and Integrity Protected Data Packet",
	"Modification Detection Code Packet"
);
ENUM_END(pgp_packet_tag_names, PGP_PKT_MOD_DETECT_CODE);

/**
 * Chained list of OpenPGP end certificates
 */
static pgpcert_t *pgpcerts   = NULL;

/**
 * Size of PGP Key ID
 */
#define PGP_KEYID_SIZE          8

const pgpcert_t pgpcert_empty = {
	  NULL     , /* next */
            0  , /* version */
			0  , /* installed */
			0  , /* count */
	{ NULL, 0 }, /* certificate */
			0  , /* created */
			0  , /* until */
	  NULL     , /* public key */
	  NULL       /* fingerprint */
};

#define PGP_INVALID_LENGTH	0xffffffff

/**
 * Returns the length of an OpenPGP (RFC 4880) packet
 * The blob pointer is advanced past the length field.
 */
static size_t pgp_length(chunk_t *blob, size_t len)
{
	size_t size = 0;

	if (len > blob->len)
	{
		return PGP_INVALID_LENGTH;
	}
	blob->len -= len;

	while (len-- > 0)
	{
		size = 256*size + *blob->ptr++;
	}
	return size;
}

/**
 * Extracts the length of a PGP packet
 */
static size_t pgp_old_packet_length(chunk_t *blob)
{
	/* bits 0 and 1 define the packet length type */
	int len_type = 0x03 & *blob->ptr++;

	blob->len--;

	/* len_type: 0 -> 1 byte, 1 -> 2 bytes, 2 -> 4 bytes */
	return pgp_length(blob, (len_type == 0)? 1: len_type << 1);
}

/**
 * Extracts PGP packet version (V3 or V4)
 */
static u_char pgp_version(chunk_t *blob)
{
	u_char version = *blob->ptr++;
	blob->len--;
	DBG(DBG_PARSING,
		DBG_log("L3 - version:");
		DBG_log("  V%d", version)
	)
	return version;
}

/**
 * Parse OpenPGP signature packet defined in section 5.2.2 of RFC 4880
 */
static bool parse_pgp_signature_packet(chunk_t *packet, pgpcert_t *cert)
{
	time_t created;
	chunk_t keyid;
	u_char  sig_type;
	u_char version = pgp_version(packet);

	/* we parse only V3 signature packets */
	if (version != 3)
	{
		return TRUE;
	}

	/* size byte must have the value 5 */
	if (pgp_length(packet, 1) != 5)
	{
		plog("  size must be 5");
		return FALSE;
	}

	/* signature type - 1 byte */
	sig_type = (u_char)pgp_length(packet, 1);
	DBG(DBG_PARSING,
		DBG_log("L3 - signature type:  0x%2x", sig_type)
	)

	/* creation date - 4 bytes */
	created = (time_t)pgp_length(packet, 4);
	DBG(DBG_PARSING,
		DBG_log("L3 - created:");
		DBG_log("  %T", &cert->created, TRUE)
	)

	/* key ID of signer - 8 bytes */
	keyid.ptr = packet->ptr;
	keyid.len = PGP_KEYID_SIZE;
	DBG_cond_dump_chunk(DBG_PARSING, "L3 - key ID of signer", keyid);

   return TRUE;
}

/**
 * Parses the version and validity of an OpenPGP public key packet
 */
static bool parse_pgp_pubkey_version_validity(chunk_t *packet, pgpcert_t *cert)
{
	cert->version = pgp_version(packet);

	if (cert->version < 3 || cert->version > 4)
	{
		plog("OpenPGP packet version V%d not supported", cert->version);
		return FALSE;
	}

	/* creation date - 4 bytes */
	cert->created = (time_t)pgp_length(packet, 4);
	DBG(DBG_PARSING,
		DBG_log("L3 - created:");
		DBG_log("  %T", &cert->created, TRUE)
	)

	if (cert->version == 3)
	{
		/* validity in days - 2 bytes */
		cert->until   = (time_t)pgp_length(packet, 2);

		/* validity of 0 days means that the key never expires */
		if (cert->until > 0)
		{
			cert->until = cert->created + 24*3600*cert->until;
		}
		DBG(DBG_PARSING,
			DBG_log("L3 - until:");
			DBG_log("  %T", &cert->until, TRUE);
		)
	}
	return TRUE;
}

/**
 * Parse OpenPGP public key packet defined in section 5.5.2 of RFC 4880
 */
static bool parse_pgp_pubkey_packet(chunk_t *packet, pgpcert_t *cert)
{
	chunk_t pubkey_packet = *packet;

	if (!parse_pgp_pubkey_version_validity(packet, cert))
	{
		return FALSE;
	}

	cert->public_key = lib->creds->create(lib->creds, CRED_PUBLIC_KEY, KEY_ANY,
										  BUILD_BLOB_PGP, *packet, BUILD_END);
	if (cert->public_key == NULL)
	{
		return FALSE;
	}

	/* compute V4 or V3 fingerprint according to section 12.2 of RFC 4880 */
	if (cert->version == 4)
	{
		char pubkey_packet_header_buf[] = {
				0x99, pubkey_packet.len / 256, pubkey_packet.len % 256
			 };
		chunk_t pubkey_packet_header = chunk_from_buf(pubkey_packet_header_buf);
		chunk_t hash;
		hasher_t *hasher;

		hasher = lib->crypto->create_hasher(lib->crypto, HASH_SHA1);
		if (hasher == NULL)
		{
			plog("no SHA-1 hasher available");
			return FALSE;
		}
		hasher->allocate_hash(hasher, pubkey_packet_header, NULL);
		hasher->allocate_hash(hasher, pubkey_packet, &hash);
		hasher->destroy(hasher);
		cert->fingerprint = identification_create_from_encoding(ID_KEY_ID, hash);
		free(hash.ptr);
	}
	else
	{
		/* V3 fingerprint is computed by public_key_t class */
		cert->fingerprint = cert->public_key->get_id(cert->public_key, ID_KEY_ID);
		if (cert->fingerprint == NULL)
		{
			return FALSE;
		}
	}
	return TRUE;
}

bool parse_pgp(chunk_t blob, pgpcert_t *cert)
{
	DBG(DBG_PARSING,
		DBG_log("L0 - PGP file:")
	)
	DBG_cond_dump_chunk(DBG_RAW, "", blob);

	if (cert == NULL)
	{
		/* should not occur, nothing to parse */
		return FALSE;
	}
	
	/* parse a PGP certificate file */
	cert->certificate = blob;
	time(&cert->installed);

	while (blob.len > 0)
	{
		chunk_t packet = chunk_empty;
		u_char packet_tag = *blob.ptr;

		DBG(DBG_PARSING,
			DBG_log("L1 - PGP packet:  tag= 0x%2x", packet_tag)
		)

		/* bit 7 must be set */
		if (!(packet_tag & 0x80))
		{
			plog("  incorrect Packet Tag");
			return FALSE;
		}

		/* bit 6 set defines new packet format */
		if (packet_tag & 0x40)
		{
			plog("  new PGP packet format not supported");
			return FALSE;
		}
		else
		{
			int packet_type = (packet_tag & 0x3C) >> 2;

			packet.len = pgp_old_packet_length(&blob);
			packet.ptr = blob.ptr;
			blob.ptr += packet.len;
			blob.len -= packet.len;
			DBG(DBG_PARSING,
				DBG_log("  %N (%d), old format, %u bytes",
						pgp_packet_tag_names, packet_type,
						packet_type, packet.len);
				DBG_log("L2 - body:")
			)
			DBG_cond_dump_chunk(DBG_RAW, "", packet);

			/* parse a PGP certificate */
			switch (packet_type)
			{
				case PGP_PKT_PUBLIC_KEY:
					if (!parse_pgp_pubkey_packet(&packet, cert))
					{
						return FALSE;
					}
					break;
				case PGP_PKT_SIGNATURE:
					if (!parse_pgp_signature_packet(&packet, cert))
					{
						return FALSE;
					}
					break;
				case PGP_PKT_USER_ID:
					DBG(DBG_PARSING,
						DBG_log("L3 - user ID:");
						DBG_log("  '%.*s'", (int)packet.len, packet.ptr)
					)
					break;
				default:
					break;
			}
		}
	}
	return TRUE;
}

/**
 *  Compare two OpenPGP certificates
 */
static bool same_pgpcert(pgpcert_t *a, pgpcert_t *b)
{
	return a->certificate.len == b->certificate.len &&
		memeq(a->certificate.ptr, b->certificate.ptr, b->certificate.len);
}

/**
 * For each link pointing to the certificate increase the count by one
 */
void share_pgpcert(pgpcert_t *cert)
{
	if (cert != NULL)
	{
		cert->count++;
	}
}

/**
 * Select the OpenPGP keyid as ID
 */
void select_pgpcert_id(pgpcert_t *cert, struct id *end_id)
{
	end_id->kind = ID_KEY_ID;
	end_id->name = cert->fingerprint->get_encoding(cert->fingerprint);
}

/**
 *  Add an OpenPGP user/host certificate to the chained list
 */
pgpcert_t* add_pgpcert(pgpcert_t *cert)
{
	pgpcert_t *c = pgpcerts;

	while (c != NULL)
	{
		if (same_pgpcert(c, cert)) /* already in chain, free cert */
		{
			free_pgpcert(cert);
			return c;
		}
		c = c->next;
	}

	/* insert new cert at the root of the chain */
	cert->next = pgpcerts;
	pgpcerts = cert;
	DBG(DBG_CONTROL | DBG_PARSING,
		DBG_log("  pgp cert inserted")
	)
	return cert;
}

/**
 *  Release of a certificate decreases the count by one.
 *  The certificate is freed when the counter reaches zero
 */
void release_pgpcert(pgpcert_t *cert)
{
	if (cert != NULL && --cert->count == 0)
	{
		pgpcert_t **pp = &pgpcerts;
		while (*pp != cert)
		{
			pp = &(*pp)->next;
		}
		*pp = cert->next;
		free_pgpcert(cert);
	}
}

/**
 *  Free a PGP certificate
 */
void free_pgpcert(pgpcert_t *cert)
{
	if (cert != NULL)
	{
		DESTROY_IF(cert->public_key);
		DESTROY_IF(cert->fingerprint);
		free(cert->certificate.ptr);
		free(cert);
	}
}

/**
 *  List all PGP end certificates in a chained list
 */
void list_pgp_end_certs(bool utc)
{
   pgpcert_t *cert = pgpcerts;
   time_t now;

	/* determine the current time */
	time(&now);

	if (cert != NULL)
	{
		whack_log(RC_COMMENT, " ");
		whack_log(RC_COMMENT, "List of PGP End certificates:");
		whack_log(RC_COMMENT, " ");
	}

	while (cert != NULL)
	{
		public_key_t *key = cert->public_key;
		cert_t c;

		c.type = CERT_PGP;
		c.u.pgp = cert;

		whack_log(RC_COMMENT, "%T, count: %d", &cert->installed, utc, cert->count);
		whack_log(RC_COMMENT, "       digest:   %Y", cert->fingerprint);
		whack_log(RC_COMMENT, "       created:  %T", &cert->created, utc);
		whack_log(RC_COMMENT, "       until:    %T %s", &cert->until, utc,
				check_expiry(cert->until, CA_CERT_WARNING_INTERVAL, TRUE));
		whack_log(RC_COMMENT, "       pubkey:   %N %4d bits%s",
				key_type_names, key->get_type(key),
				key->get_keysize(key) * BITS_PER_BYTE,				
				has_private_key(c)? ", has private key" : "");
		whack_log(RC_COMMENT, "       keyid:    %Y",
				key->get_id(key, ID_PUBKEY_INFO_SHA1));
		cert = cert->next;
	}
}

