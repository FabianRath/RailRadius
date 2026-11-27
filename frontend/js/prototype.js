// PROTOTYPE (branch prototype/redesign, never merged): redesign variants and basemaps, switched by
// ?variant=A|B|C and ?basemap=1|2|12|L. Without either param the page is unchanged and no bar shows.

const VARIANTS = [
    { key: 'off', name: 'Current (Abfahrt)' },
    { key: 'A', name: 'Atlas' },
    { key: 'B', name: 'Quiet app' },
    { key: 'C', name: 'Sentence' },
];
const BASEMAPS = [
    { key: 'off', name: 'Current OSM dark' },
    { key: '1', name: 'Rail-only OSM' },
    { key: '2', name: 'Our network' },
    { key: '12', name: 'Network → OSM tracks' },
    { key: 'L', name: 'OSM tracks + our labels' },
];

const params = new URLSearchParams(location.search);
const active = params.has('variant') || params.has('basemap');

export const variant = pick(VARIANTS, params.get('variant'));
export const basemap = pick(BASEMAPS, params.get('basemap'));

function pick(options, key) {
    return options.find(option => option.key === key) ?? options[0];
}

async function loadVariantCss() {
    if (variant.key === 'off') return;
    document.documentElement.dataset.variant = variant.key.toLowerCase();
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = `css/prototype-${variant.key.toLowerCase()}.css`;
    const loaded = new Promise(resolve => { link.onload = resolve; link.onerror = resolve; });
    document.head.append(link);
    await loaded;
}

function go(param, options, current, step) {
    const index = (options.indexOf(current) + step + options.length) % options.length;
    const next = new URLSearchParams(location.search);
    next.set(param, options[index].key);
    location.search = next.toString();
}

function group(title, param, options, current, keys) {
    const wrap = document.createElement('div');
    wrap.className = 'proto-group';
    const prev = document.createElement('button');
    prev.textContent = '‹';
    prev.title = keys[0];
    prev.onclick = () => go(param, options, current, -1);
    const label = document.createElement('span');
    label.innerHTML = `<small>${title}</small>${current.key} · ${current.name}`;
    const next = document.createElement('button');
    next.textContent = '›';
    next.title = keys[1];
    next.onclick = () => go(param, options, current, 1);
    wrap.append(prev, label, next);
    return wrap;
}

function buildBar() {
    const bar = document.createElement('div');
    bar.className = 'proto-bar';
    bar.append(group('UI  ← →', 'variant', VARIANTS, variant, ['←', '→']),
        group('Basemap  [ ]', 'basemap', BASEMAPS, basemap, ['[', ']']));
    document.body.append(bar);
    document.addEventListener('keydown', event => {
        if (event.target.closest('input, textarea, [contenteditable]')) return;
        if (event.key === 'ArrowLeft') go('variant', VARIANTS, variant, -1);
        if (event.key === 'ArrowRight') go('variant', VARIANTS, variant, 1);
        if (event.key === '[') go('basemap', BASEMAPS, basemap, -1);
        if (event.key === ']') go('basemap', BASEMAPS, basemap, 1);
    });
}

if (active) {
    await loadVariantCss();
    const style = document.createElement('link');
    style.rel = 'stylesheet';
    style.href = 'css/prototype-bar.css';
    document.head.append(style);
    buildBar();
}
