const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');

const originalLoad = Module._load;
Module._load = function (request, parent, isMain) {
  switch (request) {
    case '@nativescript/core':
      return { Utils: { android: { getApplicationContext: () => ({}) } } };
    case '../apps/external/extension-providers':
      return {
        ExtensionSttClient: class {},
        extensionTranscriptionAvailable: () => false,
      };
    case './cloud-stt':
      return {};
    case './reconnecting-stt':
      return { ReconnectingSttClient: class {} };
    case './speech-pause':
      return {
        SpeechPauseDetector: class {
          reset() {}
          accept() {
            return false;
          }
        },
      };
    case './elevenlabs-stt':
      return { ElevenLabsSttClient: class {} };
    case './openai-stt':
      return { OpenAiRealtimeSttClient: class {} };
    case './soniox-stt':
      return { SonioxSttClient: class {} };
    case '../util/array-util':
      return { toUint8Array: (value) => Uint8Array.from(value) };
    default:
      return originalLoad.call(this, request, parent, isMain);
  }
};

class FakeController {
  constructor() {
    FakeController.instance = this;
    this.starts = [];
    this.stopCalls = 0;
    this.abortCalls = 0;
    this.capturing = false;
    this.captureIds = [];
  }
  setListener(listener) {
    this.listener = listener;
  }
  setCommunicator() {}
  setUsePhoneMic() {}
  setSaveRecordings() {}
  setEndpointing() {}
  setNoiseSuppression() {}
  setBeamFilter() {}
  setOnboardModelKind() {}
  clearSpeakerVerification() {}
  setSpeakerVerification() {}
  hasOnboardModel() {
    return true;
  }
  isCapturing() {
    return this.capturing;
  }
  start(mode, captureId = 0) {
    this.starts.push(mode);
    this.captureIds.push(captureId);
    this.capturing = true;
  }
  stop() {
    this.stopCalls++;
    this.capturing = false;
  }
  abort() {
    this.abortCalls++;
    this.capturing = false;
  }
  finish() {
    this.listener.onStopped(this.captureIds.at(-1) ?? 0);
  }
}

global.isAndroid = true;
global.com = {
  faceclaw: {
    app: {
      FaceclawVoiceController: FakeController,
      FaceclawVoiceControllerListener: class {
        constructor(implementation) {
          return implementation;
        }
      },
    },
  },
};
global.Array = Object.assign(Array, {
  create(_type, length) {
    return new Array(length).fill(0);
  },
});

const sourcePath = path.resolve('app/native/voice-control.ts');
const source = fs.readFileSync(sourcePath, 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  fileName: sourcePath,
}).outputText;
const voiceModule = new Module(sourcePath, module);
voiceModule.filename = sourcePath;
voiceModule.paths = Module._nodeModulePaths(path.dirname(sourcePath));
voiceModule._compile(compiled, sourcePath);
Module._load = originalLoad;
const { FaceclawVoiceControlBridge } = voiceModule.exports;

const options = {
  communicator: {},
  provider: 'onboard',
  elevenLabsApiKey: '',
  openAiApiKey: '',
  sonioxApiKey: '',
  saveRecording: false,
};

test('the TypeScript voice proxy implements every Kotlin listener callback', () => {
  const listenerSource = fs.readFileSync(
    'native/kotlin/shared/src/commonMain/kotlin/com/faceclaw/app/callbacks/FaceclawVoiceControllerListener.kt',
    'utf8',
  );
  const callbacks = [...listenerSource.matchAll(/\bfun (\w+)\(/g)].map((match) => match[1]);
  assert.ok(callbacks.includes('onStopped'));
  new FaceclawVoiceControlBridge().startPushToTalk(options);
  for (const name of callbacks) assert.equal(typeof FakeController.instance.listener[name], 'function', name);
});

test('cloud finalization waits for native final PCM', () => {
  const bridge = new FaceclawVoiceControlBridge();
  bridge.startPushToTalk(options);
  const controller = FakeController.instance;
  const events = [];
  bridge.cloudClient = {
    start() {},
    acceptPcm(bytes) {
      events.push(`pcm:${bytes.length}`);
    },
    finish() {
      events.push('finish');
    },
    stop() {
      events.push('stop');
    },
  };

  controller.listener.onPcm(Uint8Array.from([1, 2]));
  bridge.stopPushToTalk();
  assert.equal(controller.stopCalls, 1);
  assert.deepEqual(events, ['pcm:2']);

  controller.listener.onPcm(Uint8Array.from([3, 4, 5, 6]));
  assert.deepEqual(events, ['pcm:2', 'pcm:4']);
  controller.finish();
  assert.deepEqual(events, ['pcm:2', 'pcm:4', 'finish']);
});

test('a replacement capture waits for the previous native worker', () => {
  const bridge = new FaceclawVoiceControlBridge();
  bridge.startPushToTalk(options);
  const controller = FakeController.instance;
  bridge.stopPushToTalk();
  bridge.startPushToTalk(options);

  assert.equal(controller.starts.length, 1);
  assert.equal(controller.stopCalls, 1);
  controller.finish();
  assert.equal(controller.starts.length, 2);
});

test('STT preemption waits for the raw worker to abort', () => {
  const bridge = new FaceclawVoiceControlBridge();
  assert.equal(bridge.startRawCapture({ communicator: {} }), true);
  const controller = FakeController.instance;
  bridge.startPushToTalk(options);

  assert.deepEqual(controller.starts, ['cloud']);
  assert.equal(controller.abortCalls, 1);
  controller.finish();
  assert.deepEqual(controller.starts, ['cloud', 'onboard']);
});

test('a queued capture released before completion never starts', () => {
  const bridge = new FaceclawVoiceControlBridge();
  bridge.startPushToTalk(options);
  const controller = FakeController.instance;
  bridge.stopPushToTalk();
  bridge.startPushToTalk(options);
  bridge.stopPushToTalk();
  controller.finish();
  assert.equal(controller.starts.length, 1);
});

test('an abort stops cloud work immediately and cannot restart stale capture', () => {
  const bridge = new FaceclawVoiceControlBridge();
  bridge.startPushToTalk(options);
  const controller = FakeController.instance;
  let cloudStops = 0;
  bridge.cloudClient = {
    start() {},
    acceptPcm() {},
    finish() {},
    stop() {
      cloudStops++;
    },
  };

  bridge.stop();
  assert.equal(controller.abortCalls, 1);
  assert.equal(cloudStops, 1);
  controller.finish();
  assert.equal(controller.starts.length, 1);
});
