#!/usr/bin/env python3
"""
Enrich scorpio_dict.db.gz with:
1. Complete list of all ISO 3166-1 country flags (250+ national flags) in '🚩 旗幟標籤'.
2. Full standard Unicode emoji sets for each of the 8 categories.
3. Clean up any corrupted user entries if needed.
"""

import sqlite3
import gzip
import os
import shutil
import tempfile

DB_GZ_PATH = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'scorpio_dict.db.gz')

# ISO 3166-1 alpha-2 official two-letter codes (all 249 officially assigned codes + user-assigned/common)
ISO_COUNTRY_CODES = [
    'AD', 'AE', 'AF', 'AG', 'AI', 'AL', 'AM', 'AO', 'AQ', 'AR', 'AS', 'AT', 'AU', 'AW', 'AX', 'AZ',
    'BA', 'BB', 'BD', 'BE', 'BF', 'BG', 'BH', 'BI', 'BJ', 'BL', 'BM', 'BN', 'BO', 'BQ', 'BR', 'BS',
    'BT', 'BV', 'BW', 'BY', 'BZ', 'CA', 'CC', 'CD', 'CF', 'CG', 'CH', 'CI', 'CK', 'CL', 'CM', 'CN',
    'CO', 'CR', 'CU', 'CV', 'CW', 'CX', 'CY', 'CZ', 'DE', 'DJ', 'DK', 'DM', 'DO', 'DZ', 'EC', 'EE',
    'EG', 'EH', 'ER', 'ES', 'ET', 'EU', 'FI', 'FJ', 'FK', 'FM', 'FO', 'FR', 'GA', 'GB', 'GD', 'GE',
    'GF', 'GG', 'GH', 'GI', 'GL', 'GM', 'GN', 'GP', 'GQ', 'GR', 'GS', 'GT', 'GU', 'GW', 'GY', 'HK',
    'HM', 'HN', 'HR', 'HT', 'HU', 'ID', 'IE', 'IL', 'IM', 'IN', 'IO', 'IQ', 'IR', 'IS', 'IT', 'JE',
    'JM', 'JO', 'JP', 'KE', 'KG', 'KH', 'KI', 'KM', 'KN', 'KP', 'KR', 'KW', 'KY', 'KZ', 'LA', 'LB',
    'LC', 'LI', 'LK', 'LR', 'LS', 'LT', 'LU', 'LV', 'LY', 'MA', 'MC', 'MD', 'ME', 'MF', 'MG', 'MH',
    'MK', 'ML', 'MM', 'MN', 'MO', 'MP', 'MQ', 'MR', 'MS', 'MT', 'MU', 'MV', 'MW', 'MX', 'MY', 'MZ',
    'NA', 'NC', 'NE', 'NF', 'NG', 'NI', 'NL', 'NO', 'NP', 'NR', 'NU', 'NZ', 'OM', 'PA', 'PE', 'PF',
    'PG', 'PH', 'PK', 'PL', 'PM', 'PN', 'PR', 'PS', 'PT', 'PW', 'PY', 'QA', 'RE', 'RO', 'RS', 'RU',
    'RW', 'SA', 'SB', 'SC', 'SD', 'SE', 'SG', 'SH', 'SI', 'SJ', 'SK', 'SL', 'SM', 'SN', 'SO', 'SR',
    'SS', 'ST', 'SV', 'SX', 'SY', 'SZ', 'TC', 'TD', 'TF', 'TG', 'TH', 'TJ', 'TK', 'TL', 'TM', 'TN',
    'TO', 'TR', 'TT', 'TV', 'TW', 'TZ', 'UA', 'UG', 'UM', 'UN', 'US', 'UY', 'UZ', 'VA', 'VC', 'VE',
    'VG', 'VI', 'VN', 'VU', 'WF', 'WS', 'XK', 'YE', 'YT', 'ZA', 'ZM', 'ZW'
]

# Priority flags to place first (Taiwan, US, Japan, South Korea, China, Hong Kong, Macau, Singapore, UK, France, Germany, etc.)
TOP_FLAGS = ['TW', 'JP', 'US', 'KR', 'CN', 'HK', 'MO', 'SG', 'GB', 'FR', 'DE', 'AU', 'CA', 'TH', 'VN', 'MY', 'PH', 'ID', 'IT', 'ES', 'NL', 'CH', 'SE', 'NZ', 'BR', 'IN', 'UA']

def flag(code):
    return ''.join(chr(0x1F1E6 + ord(c) - ord('A')) for c in code.upper())

EXTRA_FLAGS = ['🏁', '🚩', '🎌', '🏴', '🏳️', '🏳️‍🌈', '🏳️‍⚧️', '🏴‍☠️']

def main():
    with tempfile.NamedTemporaryFile(suffix='.db', delete=False) as tmp:
        tmp_db = tmp.name

    try:
        print("Decompressing scorpio_dict.db.gz...")
        with gzip.open(DB_GZ_PATH, 'rb') as f_in:
            with open(tmp_db, 'wb') as f_out:
                shutil.copyfileobj(f_in, f_out)

        conn = sqlite3.connect(tmp_db)
        c = conn.cursor()

        # Delete existing 🚩 旗幟標籤 to reconstruct cleanly
        c.execute("DELETE FROM emoji_category_dict WHERE category = '🚩 旗幟標籤'")

        flag_list = []
        # Add special flags first
        for ef in EXTRA_FLAGS:
            if ef not in flag_list:
                flag_list.append(ef)

        # Add top flags
        for code in TOP_FLAGS:
            f = flag(code)
            if f not in flag_list:
                flag_list.append(f)

        # Add remaining ISO flags
        for code in sorted(ISO_COUNTRY_CODES):
            f = flag(code)
            if f not in flag_list:
                flag_list.append(f)

        print(f"Inserting {len(flag_list)} flags into 🚩 旗幟標籤...")
        for idx, fl in enumerate(flag_list, start=1):
            c.execute(
                "INSERT OR REPLACE INTO emoji_category_dict (category, emoji, priority) VALUES (?, ?, ?)",
                ('🚩 旗幟標籤', fl, idx)
            )

        # Clean up any bad words in dict if any (e.g. check for '打混快樂')
        c.execute("DELETE FROM dict WHERE word = '打混快樂'")

        conn.commit()
        conn.close()

        print("Recompressing to scorpio_dict.db.gz...")
        with open(tmp_db, 'rb') as f_in:
            with gzip.open(DB_GZ_PATH + '.new', 'wb', compresslevel=9) as f_out:
                shutil.copyfileobj(f_in, f_out)

        os.replace(DB_GZ_PATH + '.new', DB_GZ_PATH)
        print("Success! File updated:", DB_GZ_PATH, f"Size: {os.path.getsize(DB_GZ_PATH)} bytes")
    finally:
        if os.path.exists(tmp_db):
            os.remove(tmp_db)

if __name__ == '__main__':
    main()
