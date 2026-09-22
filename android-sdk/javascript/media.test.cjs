const test = require('node:test');
const assert = require('node:assert/strict');
const {decodeMediaArtwork} = require('./media');
test('optional media decoder bounds input and preserves grayscale levels', () => {
 const art = decodeMediaArtwork({width: 2, height: 2, gray4: '05af'});
 assert.deepEqual([...art.pixels], [0,85,170,255]);
 for (const value of [null, {}, {width:65,height:1,gray4:'0'.repeat(65)}, {width:1,height:1,gray4:'ff'}, {width:1,height:1,gray4:'G'}, {width:0,height:1,gray4:''}]) assert.equal(decodeMediaArtwork(value), null);
});

test('missing native widget metadata fails explicitly instead of silently losing declarations', () => {
 const {appControls} = require('./controls');
 const registry = {version: 1, widgets: []};
 assert.equal(appControls(null).registerGlanceboardWidgets(registry), false);
 assert.throws(() => appControls({}).registerGlanceboardWidgets(registry), /SDK bridge metadata missing registerGlanceboardWidgets/);
 const sdk = appControls({registerGlanceboardWidgets: value => value.version === 1}, {json: value => value});
 assert.equal(sdk.registerGlanceboardWidgets(registry), true);
 sdk.dispose(); assert.equal(sdk.registerGlanceboardWidgets(registry), false);
});
