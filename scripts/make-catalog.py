"""Turn Fire OS's content-support catalogues into the app's voice catalogue.

Usage: make-catalog.py CONTENTSUPPORT_JSON OLDER_CONTENTSUPPORT_JSON KINDLE_DIR OUT_JSON OUT_ASSETS
Only the TTS voices are kept. Each pack downloads from Amazon's own bucket, the
same address a Fire tablet builds from the entry. Classic voices the older
catalogue lists but the newer one replaced with neural ones stay on offer
beside them, keyed with a .classic suffix. Joanna, from the Kindle files in
KINDLE_DIR, is built on the phone from neural Salli's pack with her own model
and metadata, which go into OUT_ASSETS.
"""
import json
import os
import re
import sys

# Packs from Ivona build 3586 (2025) are the compact neural voices; every other
# build is a classic unit-selection voice.
NEURAL_BUILDS = {'3586'}


def voices(path):
    out = {}
    for p in json.load(open(path))['supportPacks']:
        if p.get('type') != 'TTS_VOICE':
            continue
        key = p['install_location'].strip('/').rsplit('/', 1)[1]
        if not re.fullmatch(r'[a-z]{3}\.[a-z]{3}\.[a-z]+', key):
            sys.exit(f'make-catalog: unexpected install location {p["install_location"]}')
        build = p['source_url'].split('/')[2]
        out[key] = {
            'key': key,
            'name': p['display_name'],
            'languages': p.get('languages') or [p['language']],
            'kind': 'neural' if build in NEURAL_BUILDS else 'classic',
            'url': 'https://s3.amazonaws.com/' + p['source_url'],
            'bytes': int(p['size']),
            'version': p['current_version'],
        }
    return out


def joanna(kindle, salli, assets):
    """
    Joanna as the engine will know her: named joana, so that her name fits
    where Salli's sits in the identity library borrowed from Salli's pack.
    """
    name, base = 'joana', salli['key'].rsplit('.', 1)[1]
    if len(name) != len(base):
        sys.exit('make-catalog: a borrowed voice name must be as long as the one it replaces')
    out = os.path.join(assets, 'voices', 'eng.usa.joanna')
    os.makedirs(os.path.join(out, 'addons'), exist_ok=True)

    model = open(os.path.join(kindle, 'en_us_joanna24_a11y.mpa'), 'rb').read()
    # The model names its voice in a MessagePack string, whose length (with
    # its NUL) is in the low bits of the byte before it.
    old, new = b'vox_en_us_joanna\0', f'vox_en_us_{name}\0'.encode()
    old, new = bytes([0xa0 | len(old)]) + old, bytes([0xa0 | len(new)]) + new
    if model.count(old) != 1:
        sys.exit('make-catalog: Joanna\'s model does not name her as expected')
    with open(os.path.join(out, 'addons', 'lightspeech_mgc.mpa'), 'wb') as f:
        f.write(model.replace(old, new))

    meta = json.load(open(os.path.join(kindle, 'en_us_joanna24_a11y.json')))
    meta['name'] = f'en_us_{name}'
    meta['lex_addenda_file_path'] = f'en_us_{name}_addenda.lex'
    with open(os.path.join(out, f'en_us_{name}24_a11y.json'), 'w') as f:
        json.dump(meta, f, indent=2)
        f.write('\n')
    with open(os.path.join(kindle, 'en_us_joanna_addenda.lex'), 'rb') as src, \
            open(os.path.join(out, f'en_us_{name}_addenda.lex'), 'wb') as dst:
        dst.write(src.read())

    return dict(salli, key='eng.usa.joanna', name='Joanna', engine=f'eng.usa.{name}',
                base=salli['key'], overlay='voices/eng.usa.joanna',
                version=salli['version'] + ' with Kindle 5.20.1')


def main():
    if len(sys.argv) != 6:
        sys.exit(__doc__)
    current, older, kindle, out, assets = sys.argv[1:]
    now, then = voices(current), voices(older)
    catalog = list(now.values())
    for key, v in then.items():
        if v['kind'] == 'classic' and now.get(key, {}).get('kind') == 'neural':
            catalog.append(dict(v, key=key + '.classic', engine=key))
    catalog.append(joanna(kindle, now['eng.usa.salli'], assets))
    catalog.sort(key=lambda v: (v['languages'][0], v['name'], v['kind'] == 'classic'))
    with open(out, 'w') as f:
        json.dump({'voices': catalog}, f, indent=1, ensure_ascii=False)
        f.write('\n')
    print(f'make-catalog: {len(catalog)} voices, '
          f'{sum(v["kind"] == "neural" for v in catalog)} neural, '
          f'{len({v["languages"][0] for v in catalog})} languages')


main()
