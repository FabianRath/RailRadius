// Approximate bounding boxes of the countries the backend can report (the ISO codes in borders/europe.geojson),
// so the first view can fit the covered countries. Mainland extent only: remote islands without rail
// (Svalbard, Canaries, Azores, Crete, Bornholm, Shetland) would only zoom the map out.

/** ISO 3166-1 alpha-2 → [west, south, east, north] in degrees. */
const COUNTRY_BOUNDS = {
    AD: [1.4, 42.4, 1.8, 42.7],
    AL: [19.3, 39.6, 21.1, 42.7],
    AT: [9.5, 46.4, 17.2, 49.0],
    AX: [19.5, 59.9, 21.1, 60.5],
    BA: [15.7, 42.6, 19.6, 45.3],
    BE: [2.5, 49.5, 6.4, 51.5],
    BG: [22.4, 41.2, 28.6, 44.2],
    BY: [23.2, 51.3, 32.8, 56.2],
    CH: [5.9, 45.8, 10.5, 47.8],
    CZ: [12.1, 48.6, 18.9, 51.1],
    DE: [5.9, 47.3, 15.0, 55.1],
    DK: [8.1, 54.6, 12.7, 57.8],
    EE: [21.8, 57.5, 28.2, 59.7],
    ES: [-9.3, 36.0, 3.3, 43.8],
    FI: [20.6, 59.8, 31.6, 70.1],
    FO: [-7.7, 61.4, -6.3, 62.4],
    FR: [-4.8, 41.4, 9.6, 51.1],
    GB: [-8.2, 49.9, 1.8, 58.7],
    GG: [-2.7, 49.4, -2.5, 49.5],
    GI: [-5.4, 36.1, -5.3, 36.2],
    GR: [20.0, 36.7, 26.6, 41.8],
    HR: [13.5, 42.4, 19.5, 46.6],
    HU: [16.1, 45.7, 22.9, 48.6],
    IE: [-10.5, 51.4, -6.0, 55.4],
    IM: [-4.8, 54.0, -4.3, 54.4],
    IS: [-24.5, 63.3, -13.5, 66.6],
    IT: [6.6, 36.6, 18.5, 47.1],
    JE: [-2.3, 49.2, -2.0, 49.3],
    LI: [9.5, 47.0, 9.7, 47.3],
    LT: [21.0, 53.9, 26.8, 56.5],
    LU: [5.7, 49.4, 6.5, 50.2],
    LV: [20.9, 55.7, 28.2, 58.1],
    MC: [7.4, 43.7, 7.5, 43.8],
    MD: [26.6, 45.5, 30.1, 48.5],
    ME: [18.4, 41.9, 20.4, 43.6],
    MK: [20.5, 40.8, 23.0, 42.4],
    MT: [14.2, 35.8, 14.6, 36.1],
    NL: [3.4, 50.8, 7.2, 53.6],
    NO: [4.6, 58.0, 31.1, 71.2],
    PL: [14.1, 49.0, 24.2, 54.9],
    PT: [-9.5, 37.0, -6.2, 42.2],
    RO: [20.3, 43.6, 29.7, 48.3],
    RS: [18.8, 42.2, 23.0, 46.2],
    RU: [19.6, 41.2, 60.0, 69.9],
    SE: [11.1, 55.3, 24.2, 69.1],
    SI: [13.4, 45.4, 16.6, 46.9],
    SK: [16.8, 47.7, 22.6, 49.6],
    SM: [12.4, 43.9, 12.5, 44.0],
    UA: [22.1, 44.4, 40.2, 52.4],
    VA: [12.4, 41.9, 12.5, 41.9],
    XK: [20.0, 41.8, 21.8, 43.3],
};

/** The union of the known countries' boxes as [[west, south], [east, north]]; null if no code is known. */
export function boundsOfCountries(codes) {
    const boxes = codes.filter(code => Object.hasOwn(COUNTRY_BOUNDS, code)).map(code => COUNTRY_BOUNDS[code]);
    if (boxes.length === 0) return null;
    return [
        [Math.min(...boxes.map(b => b[0])), Math.min(...boxes.map(b => b[1]))],
        [Math.max(...boxes.map(b => b[2])), Math.max(...boxes.map(b => b[3]))],
    ];
}
