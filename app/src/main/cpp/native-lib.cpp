#include <jni.h>

#include "AudioEngine.h"

// Function names are mangled from the fully-qualified Kotlin class:
//   com.resonix.player.audio.NativeAudioEngine
//        -> Java_com_resonix_player_audio_NativeAudioEngine_<method>
// Rename these to match if the package or class name ever changes.

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeCreate(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    auto *engine = new resonix::AudioEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeDestroy(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    delete reinterpret_cast<resonix::AudioEngine *>(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativePlayTrack(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring filePath) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine == nullptr || filePath == nullptr) {
        return JNI_FALSE;
    }
    const char *pathChars = env->GetStringUTFChars(filePath, nullptr);
    bool ok = engine->playTrack(std::string(pathChars));
    env->ReleaseStringUTFChars(filePath, pathChars);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativePlayTrackFd(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jint fd) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine == nullptr || fd < 0) {
        return JNI_FALSE;
    }
    return engine->playTrackFd(static_cast<int>(fd)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativePauseTrack(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->pauseTrack();
    }
}

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeResumeTrack(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->resumeTrack();
    }
}

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeSeekTo(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jlong positionMs) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->seekTo(positionMs);
    }
}

JNIEXPORT jlong JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetCurrentPositionMs(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getCurrentPositionMs() : 0L;
}

JNIEXPORT jboolean JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeIsPlaying(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return (engine != nullptr && engine->isPlaying()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeConsumeTrackFinished(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return (engine != nullptr && engine->consumeTrackFinishedEvent()) ? JNI_TRUE : JNI_FALSE;
}

// --- Track info getters -----------------------------------------------
// Kept as individual primitive-returning functions and assembled into
// an AudioTrackInfo on the Kotlin side (NativeAudioEngine.kt), rather
// than constructing a Kotlin object from JNI.

JNIEXPORT jint JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetSampleRate(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().sampleRateHz : 0;
}

JNIEXPORT jint JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetBitDepth(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().bitDepth : 0;
}

JNIEXPORT jint JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetChannelCount(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().channelCount : 0;
}

JNIEXPORT jlong JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetBitrateBps(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().bitrateBps : 0L;
}

JNIEXPORT jlong JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetDurationMs(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().durationMs : 0L;
}

JNIEXPORT jstring JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetFormatName(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    std::string name = engine != nullptr ? engine->getAudioTrackInfo().formatName : "";
    return env->NewStringUTF(name.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetTitle(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    std::string value = engine != nullptr ? engine->getAudioTrackInfo().title : "";
    return env->NewStringUTF(value.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetArtist(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    std::string value = engine != nullptr ? engine->getAudioTrackInfo().artist : "";
    return env->NewStringUTF(value.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetAlbum(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    std::string value = engine != nullptr ? engine->getAudioTrackInfo().album : "";
    return env->NewStringUTF(value.c_str());
}

JNIEXPORT jint JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetYear(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().year : 0;
}

JNIEXPORT jdouble JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetTrackGainDb(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().trackGainDb : 0.0;
}

JNIEXPORT jdouble JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetTrackPeak(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().trackPeakLinear : 1.0;
}

// --- Output Plugin System: AudioTrack fallback --------------------------

JNIEXPORT jint JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativePullSamples(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jfloatArray outArray,
        jint maxFrames, jint channelCount) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine == nullptr || outArray == nullptr) {
        return 0;
    }
    jfloat *outPtr = env->GetFloatArrayElements(outArray, nullptr);
    if (outPtr == nullptr) {
        return 0;
    }
    int32_t framesWritten = engine->pullProcessedFrames(outPtr, maxFrames, channelCount);
    env->ReleaseFloatArrayElements(outArray, outPtr, 0);
    return framesWritten;
}

// --- Direct Volume Control ---------------------------------------------

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeSetGain(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jdouble linearGain) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->setGain(linearGain);
    }
}

JNIEXPORT jdouble JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetGain(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getGain() : 1.0;
}

// --- Settings -> Audio engine --------------------------------------------

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeSetOutputConfig(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jint api, jboolean exclusive,
        jboolean bitPerfect) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->setOutputConfig(static_cast<int>(api), exclusive == JNI_TRUE, bitPerfect == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeSetReplayGainMode(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jint mode) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->setReplayGainMode(static_cast<int>(mode));
    }
}

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeSetLimiterEnabled(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jboolean enabled) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->setLimiterEnabled(enabled == JNI_TRUE);
    }
}

JNIEXPORT jstring JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetOutputDescription(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    std::string value = engine != nullptr ? engine->getOutputDescription() : "";
    return env->NewStringUTF(value.c_str());
}

// --- Tools -> Sound shaping ----------------------------------------------

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeSetSoundShaping(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jdouble preampDb, jdouble bassBoost,
        jdouble stereoWidth, jdouble balance) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->setPreampDb(preampDb);
        engine->setBassBoost(bassBoost);
        engine->setStereoWidth(stereoWidth);
        engine->setBalance(balance);
    }
}

JNIEXPORT jdouble JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetAlbumGainDb(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getAudioTrackInfo().albumGainDb : 0.0;
}

// --- Output plan / fidelity ----------------------------------------------

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeSetOutputPlan(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jint deviceId, jint forcedRateHz,
        jint formatPref, jboolean bitPerfectMixer, jint nativeRateHz) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->setOutputPlan(static_cast<int>(deviceId), static_cast<int>(forcedRateHz),
                              static_cast<int>(formatPref), bitPerfectMixer == JNI_TRUE,
                              static_cast<int>(nativeRateHz));
    }
}

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeSetFidelity(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jint ditherMode, jint resamplerQuality) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->setFidelity(static_cast<int>(ditherMode), static_cast<int>(resamplerQuality));
    }
}

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeSetNextStartPosition(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jlong positionMs) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine != nullptr) {
        engine->setNextStartPosition(static_cast<int64_t>(positionMs));
    }
}

// --- Tools -> Equalizer ------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeSetEq(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jboolean enabled, jboolean autoHeadroom,
        jintArray types, jdoubleArray freqs, jdoubleArray gains, jdoubleArray qs,
        jbooleanArray active) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    if (engine == nullptr) {
        return;
    }
    constexpr int kMax = resonix::EqChain::kMaxBands;
    jsize count = env->GetArrayLength(types);
    count = std::min<jsize>(count, kMax);
    count = std::min<jsize>(count, env->GetArrayLength(freqs));
    count = std::min<jsize>(count, env->GetArrayLength(gains));
    count = std::min<jsize>(count, env->GetArrayLength(qs));
    count = std::min<jsize>(count, env->GetArrayLength(active));
    if (count < 0) {
        count = 0;
    }

    jint typeBuf[kMax];
    jdouble freqBuf[kMax];
    jdouble gainBuf[kMax];
    jdouble qBuf[kMax];
    jboolean activeBuf[kMax];
    if (count > 0) {
        env->GetIntArrayRegion(types, 0, count, typeBuf);
        env->GetDoubleArrayRegion(freqs, 0, count, freqBuf);
        env->GetDoubleArrayRegion(gains, 0, count, gainBuf);
        env->GetDoubleArrayRegion(qs, 0, count, qBuf);
        env->GetBooleanArrayRegion(active, 0, count, activeBuf);
    }

    resonix::EqBandParams bands[kMax];
    for (jsize i = 0; i < count; i++) {
        bands[i].type = typeBuf[i];
        bands[i].freqHz = freqBuf[i];
        bands[i].gainDb = gainBuf[i];
        bands[i].q = qBuf[i];
        bands[i].enabled = activeBuf[i] == JNI_TRUE;
    }
    engine->setEq(enabled == JNI_TRUE, autoHeadroom == JNI_TRUE, bands, static_cast<int>(count));
}

JNIEXPORT jdouble JNICALL
Java_com_resonix_player_audio_NativeAudioEngine_nativeGetEqHeadroomDb(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *engine = reinterpret_cast<resonix::AudioEngine *>(handle);
    return engine != nullptr ? engine->getEqHeadroomDb() : 0.0;
}

}  // extern "C"
