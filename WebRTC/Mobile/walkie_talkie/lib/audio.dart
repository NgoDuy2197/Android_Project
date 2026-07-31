import 'dart:async';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:record/record.dart';

/// Handles all audio:
///  - Soundboard clips: record to files ([record]) and play them ([audioplayers]).
///  - Live "walkie-talkie" audio: a two-way WebRTC audio call. Signaling is
///    relayed by the caller over the app's socket. WebRTC's built-in echo
///    cancellation handles the anti-echo requirement; the mic track is muted
///    until the user toggles talk on.
class AudioEngine {
  final AudioRecorder _clipRecorder = AudioRecorder();
  final AudioPlayer _clipPlayer = AudioPlayer();

  static const _iceConfig = {
    'iceServers': [
      {'urls': 'stun:stun.l.google.com:19302'},
    ],
    'sdpSemantics': 'unified-plan',
  };

  RTCPeerConnection? _pc;
  MediaStream? _localStream;
  void Function(Map<String, dynamic> signal)? _sendSignal;

  bool _offerer = false;
  bool _localReady = false;
  bool _remoteReady = false;
  bool _offered = false;
  bool _remoteDescSet = false;
  final List<RTCIceCandidate> _pendingCandidates = [];

  bool talking = false;

  Future<bool> hasMicPermission() => _clipRecorder.hasPermission();

  // --- Live WebRTC audio -----------------------------------------------------
  Future<void> initLive({
    required bool offerer,
    required void Function(Map<String, dynamic> signal) sendSignal,
  }) async {
    _offerer = offerer;
    _sendSignal = sendSignal;

    final pc = await createPeerConnection(_iceConfig);
    _pc = pc;

    _localStream = await navigator.mediaDevices
        .getUserMedia({'audio': true, 'video': false});
    for (final track in _localStream!.getAudioTracks()) {
      track.enabled = false; // muted until PTT
      await pc.addTrack(track, _localStream!);
    }

    pc.onIceCandidate = (c) {
      if (c.candidate != null) {
        _sendSignal?.call({'kind': 'ice', 'candidate': c.toMap()});
      }
    };

    // Route remote audio to the loudspeaker (this is a speakerphone use-case).
    try {
      await Helper.setSpeakerphoneOn(true);
    } catch (_) {}

    _localReady = true;
    _sendSignal?.call({'kind': 'ready'});
    await _maybeOffer();
  }

  Future<void> _maybeOffer() async {
    if (!_offerer || _offered || !_localReady || !_remoteReady) return;
    _offered = true;
    final offer = await _pc!.createOffer({});
    await _pc!.setLocalDescription(offer);
    _sendSignal?.call({'kind': 'offer', 'sdp': offer.sdp});
  }

  Future<void> handleSignal(Map<String, dynamic> m) async {
    final pc = _pc;
    if (pc == null) return;
    switch (m['kind']) {
      case 'ready':
        _remoteReady = true;
        await _maybeOffer();
        break;
      case 'offer':
        await pc.setRemoteDescription(RTCSessionDescription(m['sdp'], 'offer'));
        _remoteDescSet = true;
        await _drainCandidates();
        final answer = await pc.createAnswer({});
        await pc.setLocalDescription(answer);
        _sendSignal?.call({'kind': 'answer', 'sdp': answer.sdp});
        break;
      case 'answer':
        await pc.setRemoteDescription(RTCSessionDescription(m['sdp'], 'answer'));
        _remoteDescSet = true;
        await _drainCandidates();
        break;
      case 'ice':
        final c = m['candidate'] as Map;
        final cand = RTCIceCandidate(
            c['candidate'], c['sdpMid'], c['sdpMLineIndex']);
        if (_remoteDescSet) {
          await pc.addCandidate(cand);
        } else {
          _pendingCandidates.add(cand);
        }
        break;
    }
  }

  Future<void> _drainCandidates() async {
    for (final c in _pendingCandidates) {
      try {
        await _pc?.addCandidate(c);
      } catch (_) {}
    }
    _pendingCandidates.clear();
  }

  /// Enable the mic track (start transmitting).
  Future<bool> startTalk() async {
    if (_localStream == null) return false;
    for (final t in _localStream!.getAudioTracks()) {
      t.enabled = true;
    }
    talking = true;
    return true;
  }

  Future<void> stopTalk() async {
    if (_localStream != null) {
      for (final t in _localStream!.getAudioTracks()) {
        t.enabled = false;
      }
    }
    talking = false;
  }

  Future<void> closeLive() async {
    talking = false;
    _localReady = _remoteReady = _offered = _remoteDescSet = false;
    _pendingCandidates.clear();
    try {
      for (final t in _localStream?.getTracks() ?? <MediaStreamTrack>[]) {
        await t.stop();
      }
      await _localStream?.dispose();
    } catch (_) {}
    _localStream = null;
    try {
      await _pc?.close();
    } catch (_) {}
    _pc = null;
  }

  // --- Soundboard clips ------------------------------------------------------
  Future<bool> startClipRecording(String path) async {
    if (!await hasMicPermission()) return false;
    await _clipRecorder.start(
      const RecordConfig(
        encoder: AudioEncoder.aacLc,
        sampleRate: 44100,
        numChannels: 1,
      ),
      path: path,
    );
    return true;
  }

  Future<String?> stopClipRecording() => _clipRecorder.stop();

  Future<bool> isClipRecording() => _clipRecorder.isRecording();

  Future<void> playClip(String path) async {
    try {
      await _clipPlayer.stop();
      await _clipPlayer.play(DeviceFileSource(path));
    } catch (_) {}
  }

  Future<void> previewClip(String path) => playClip(path);

  Future<void> dispose() async {
    await closeLive();
    try {
      await _clipPlayer.dispose();
    } catch (_) {}
    try {
      await _clipRecorder.dispose();
    } catch (_) {}
  }
}
