WhisperC++ Transcription Engine
===============================

Overview
--------

WhisperC++ is a plain C/C++ implementation of OpenAI's Whisper automatic speech recognition (ASR) model.

Opencast can take advantage of [Open AI's Whisper Project](https://github.com/openai/whisper) to generate
automatic transcriptions on premise through [SpeechToText WoH](../../workflowoperationhandlers/speechtotext-woh.md).


Advantages
----------
- Transcription on more than 80 languages
- Fast processing also without GPU
- Run locally, no data sent to third parties.

Enable Whisper engine
---------------------
To enable Whisper as for the `SpeechToText` WoH, follow these steps.

1. [Install whispercpp](https://github.com/ggerganov/whisper.cpp/tree/master#quick-start) on the worker nodes.
2. Install language models.
3. Enable whispercpp and set Job load in `org.opencastproject.speechtotext.impl.SpeechToTextServiceImpl.cfg`.
4. Set the target model to use in `org.opencastproject.speechtotext.impl.engine.WhisperCppEngine`.


Additional Notes
----------------

- At the moment whispercpp binary has to be built manually.
- WhisperC++ can be run on CPU or GPU, the use of a GPU increases the performance - if the binary is built with
  appropriate modules.
- Language models are based on Whisper and are transformed to a custom ggml format. You can download already
  converted models or build/tweak your own model. For more informations visit the
  [README.md](https://github.com/ggerganov/whisper.cpp/blob/master/models/README.md) page for models.
- WhisperCPP processes only PCM16 (wav) audio files. Therefore you have to add an encode-operation before
  running the speechtotext-woh.

```
  - id: encode
    description: "Extract audio for speecht to text"
    configurations:
      - source-flavor: "*/themed"
      - target-flavor: "*/audio+stt"
      - encoding-profile: audio-whisper
      - target-tags: archive

  - id: speechtotext
    description: "Generates subtitles (whispercpp)"
    configurations:
      - source-flavor: "*/audio+stt"
      - target-flavor: "captions/vtt+auto-#{lang}"
      - target-element: attachment
      - language-code: de
      - target-tags: >-
          archive,
          subtitle,
          engage-download
```
- The module expects language models in the folder `/usr/share/ggml` with filename `ggml-<whispercpp.model>.bin`.

```
$ tree /usr/share/ggml
/usr/share/ggml
├── ggml-base.bin
└── ggml-tiny.bin
```


#### Not yet implemented

This is a list of features that are not implemented, they will be added in the near future.

- Translation to English
- Automatic language recognition and tagging
