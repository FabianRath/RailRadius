// Map labels in Latin script only (#12): the base style adds a place's local script (Cyrillic, Greek, Georgian, …) as
// a second line, which makes the browser fetch a font range per script, about 0.4 MB on a first view of Europe.

const NON_LATIN_NAME = 'name:nonlatin';

/**
 * The style with each label that shows a name in two scripts showing only its Latin one; every other label, and the
 * name a place without a local script shows (English where the tiles have one), stays as it was.
 */
export function withLatinLabels(style) {
    return {
        ...style,
        layers: style.layers.map(layer => {
            const field = layer.layout?.['text-field'];
            if (!showsNonLatinName(field)) return layer;
            return { ...layer, layout: { ...layer.layout, 'text-field': latinOnly(field) } };
        })
    };
}

// The base style's form: ['case', ['has', 'name:nonlatin'], <both scripts>, <name without a local script>]
function showsNonLatinName(field) {
    return Array.isArray(field) && field[0] === 'case' && field.length === 4
        && JSON.stringify(field[1]) === JSON.stringify(['has', NON_LATIN_NAME]);
}

function latinOnly(field) {
    return ['case', ['has', NON_LATIN_NAME], ['get', 'name:latin'], field[3]];
}
