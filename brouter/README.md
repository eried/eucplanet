# brouter

BRouter 1.7.10 (https://github.com/abrensch/brouter, tag v1.7.10, MIT, see
LICENSE), the sources of brouter-util, brouter-codec, brouter-expressions,
brouter-mapaccess and brouter-core in one module, used for offline routing.

Local changes: VoiceHint's command constants are public and it has getters
(command, index in track, distance to next, angle), and
VoiceHintList has getList(), so the app can read turn hints.
