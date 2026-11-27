// Joins tilesets that Planetiler wrote as {z}/{x}/{y}.pbf folders (uncompressed) into one, for scripts/rail-tiles.sh.
// A tile that several countries share gets one layer per name holding the features of all: a vector tile must not
// repeat a layer name, so the tiles can't simply be concatenated.
// Usage: node scripts/rail-tiles-merge.mjs <out dir> <tileset dir>...
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

// Protobuf wire types and the Mapbox Vector Tile field numbers used here
const VARINT = 0, FIXED64 = 1, BYTES = 2, FIXED32 = 5;
const TILE_LAYER = 3;
const LAYER_NAME = 1, LAYER_FEATURE = 2, LAYER_KEY = 3, LAYER_VALUE = 4;
const FEATURE_TAGS = 2;
const METADATA = 'metadata.json';

function readVarint(buf, pos) {
    let value = 0, shift = 0, byte;
    do {
        byte = buf[pos++];
        value += (byte & 0x7f) * 2 ** shift;
        shift += 7;
    } while (byte & 0x80);
    return [value, pos];
}

/** Every field of a message as { field, type, value }: a number for varints, raw bytes otherwise. */
function fields(buf) {
    const result = [];
    let pos = 0;
    while (pos < buf.length) {
        let key, value;
        [key, pos] = readVarint(buf, pos);
        const field = Math.floor(key / 8), type = key % 8;
        if (type === VARINT) {
            const start = pos;
            [value, pos] = readVarint(buf, pos);
            result.push({ field, type, value, raw: buf.subarray(start, pos) });
            continue;
        }
        let length = type === FIXED64 ? 8 : type === FIXED32 ? 4 : 0;
        if (type === BYTES) [length, pos] = readVarint(buf, pos);
        else if (!length) throw new Error(`Unsupported wire type ${type}`);
        result.push({ field, type, value: buf.subarray(pos, pos + length), raw: buf.subarray(pos, pos + length) });
        pos += length;
    }
    return result;
}

function varint(value) {
    const bytes = [];
    while (value >= 0x80) {
        bytes.push((value % 0x80) | 0x80);
        value = Math.floor(value / 0x80);
    }
    bytes.push(value);
    return Buffer.from(bytes);
}

function encode({ field, type, raw }) {
    const key = varint(field * 8 + type);
    return type === BYTES ? Buffer.concat([key, varint(raw.length), raw]) : Buffer.concat([key, raw]);
}

function bytesField(field, raw) {
    return encode({ field, type: BYTES, raw });
}

/** Collects layers by name; keys and values are shared per layer, so each feature's tag indices are renumbered. */
class LayerMerger {
    constructor() {
        this.header = [];
        this.keys = new Map();
        this.values = new Map();
        this.features = [];
    }

    add(layerFields) {
        const keys = layerFields.filter(f => f.field === LAYER_KEY).map(f => index(this.keys, f.raw));
        const values = layerFields.filter(f => f.field === LAYER_VALUE).map(f => index(this.values, f.raw));
        if (!this.header.length) {
            this.header = layerFields.filter(f => ![LAYER_FEATURE, LAYER_KEY, LAYER_VALUE].includes(f.field));
        }
        for (const feature of layerFields.filter(f => f.field === LAYER_FEATURE)) {
            this.features.push(renumberTags(fields(feature.raw), keys, values));
        }
    }

    encode() {
        return Buffer.concat([...this.header.map(encode),
            ...this.features.map(feature => bytesField(LAYER_FEATURE, feature)),
            ...[...this.keys.keys()].map(key => bytesField(LAYER_KEY, Buffer.from(key, 'latin1'))),
            ...[...this.values.keys()].map(value => bytesField(LAYER_VALUE, Buffer.from(value, 'latin1')))]);
    }
}

function index(table, raw) {
    const id = raw.toString('latin1');
    if (!table.has(id)) table.set(id, table.size);
    return table.get(id);
}

function renumberTags(featureFields, keys, values) {
    return Buffer.concat(featureFields.map(f => {
        if (f.field !== FEATURE_TAGS) return encode(f);
        const tags = [];
        let pos = 0, tag;
        while (pos < f.raw.length) {
            [tag, pos] = readVarint(f.raw, pos);
            tags.push(tags.length % 2 === 0 ? keys[tag] : values[tag]);
        }
        return bytesField(FEATURE_TAGS, Buffer.concat(tags.map(varint)));
    }));
}

function mergeTiles(tiles) {
    const layers = new Map();
    for (const tile of tiles) {
        for (const layer of fields(tile).filter(f => f.field === TILE_LAYER)) {
            const layerFields = fields(layer.raw);
            const name = layerFields.find(f => f.field === LAYER_NAME).raw.toString();
            if (!layers.has(name)) layers.set(name, new LayerMerger());
            layers.get(name).add(layerFields);
        }
    }
    return Buffer.concat([...layers.values()].map(layer => bytesField(TILE_LAYER, layer.encode())));
}

/** Relative paths of every tile below dir, e.g. "7/70/44.pbf". */
function tilePaths(dir) {
    return readdirSync(dir, { recursive: true }).map(path => path.replaceAll('\\', '/'))
        .filter(path => path.endsWith('.pbf'));
}

/** Planetiler's metadata of the first tileset, with bounds that cover all of them. */
function mergeMetadata(dirs) {
    const all = dirs.map(dir => JSON.parse(readFileSync(join(dir, METADATA), 'utf8')));
    const bounds = all.map(metadata => metadata.bounds.split(',').map(Number));
    const union = [0, 1, 2, 3].map(i => (i < 2 ? Math.min : Math.max)(...bounds.map(b => b[i])));
    return { ...all[0], bounds: union.join(',') };
}

const [out, ...dirs] = process.argv.slice(2);
if (!out || !dirs.length) throw new Error('Usage: node scripts/rail-tiles-merge.mjs <out dir> <tileset dir>...');
const sources = new Map();
for (const dir of dirs) {
    for (const path of tilePaths(dir)) sources.set(path, [...(sources.get(path) ?? []), join(dir, path)]);
}
let shared = 0;
for (const [path, files] of sources) {
    const tiles = files.map(file => readFileSync(file));
    if (tiles.length > 1) shared++;
    const target = join(out, path);
    mkdirSync(join(target, '..'), { recursive: true });
    writeFileSync(target, tiles.length > 1 ? mergeTiles(tiles) : tiles[0]);
}
writeFileSync(join(out, METADATA), JSON.stringify(mergeMetadata(dirs)));
console.log(`Merged ${dirs.length} tilesets into ${out}: ${sources.size} tiles, ${shared} of them shared`);
