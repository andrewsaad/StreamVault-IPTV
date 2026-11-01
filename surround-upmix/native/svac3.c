// JNI wrapper around FFmpeg's AC-3 encoder: 5.1 float PCM in (interleaved FL FR FC LFE SL SR,
// 1536 frames per call), one AC-3 frame out. Used by com.streamvault.surround.Ac3Encoder.
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>

typedef struct {
    AVCodecContext *ctx;
    AVFrame *frame;
    AVPacket *pkt;
} Enc;

static void enc_free(Enc *e) {
    if (!e) return;
    av_packet_free(&e->pkt);
    av_frame_free(&e->frame);
    avcodec_free_context(&e->ctx);
    free(e);
}

JNIEXPORT jlong JNICALL
Java_com_streamvault_surround_Ac3Encoder_nativeOpen(JNIEnv *env, jclass cls, jint sampleRate, jint bitRate) {
    const AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_AC3);
    if (!codec) return 0;
    Enc *e = calloc(1, sizeof(Enc));
    if (!e) return 0;
    e->ctx = avcodec_alloc_context3(codec);
    e->frame = av_frame_alloc();
    e->pkt = av_packet_alloc();
    if (!e->ctx || !e->frame || !e->pkt) { enc_free(e); return 0; }
    AVChannelLayout layout = AV_CHANNEL_LAYOUT_5POINT1;
    e->ctx->sample_rate = sampleRate;
    e->ctx->bit_rate = bitRate;
    e->ctx->sample_fmt = AV_SAMPLE_FMT_FLTP;
    if (av_channel_layout_copy(&e->ctx->ch_layout, &layout) < 0 || avcodec_open2(e->ctx, codec, NULL) < 0) {
        enc_free(e);
        return 0;
    }
    e->frame->nb_samples = e->ctx->frame_size;  // 1536
    e->frame->format = AV_SAMPLE_FMT_FLTP;
    e->frame->sample_rate = sampleRate;
    if (av_channel_layout_copy(&e->frame->ch_layout, &layout) < 0 || av_frame_get_buffer(e->frame, 0) < 0) {
        enc_free(e);
        return 0;
    }
    return (jlong) (intptr_t) e;
}

JNIEXPORT jint JNICALL
Java_com_streamvault_surround_Ac3Encoder_nativeFrameSize(JNIEnv *env, jclass cls, jlong handle) {
    Enc *e = (Enc *) (intptr_t) handle;
    return e ? e->ctx->frame_size : 0;
}

// Returns the AC-3 frame length written to out, 0 when the encoder produced nothing yet, -1 on error.
JNIEXPORT jint JNICALL
Java_com_streamvault_surround_Ac3Encoder_nativeEncode(JNIEnv *env, jclass cls, jlong handle,
                                                       jfloatArray pcm, jbyteArray out) {
    Enc *e = (Enc *) (intptr_t) handle;
    if (!e) return -1;
    const int n = e->ctx->frame_size, ch = 6;
    if ((*env)->GetArrayLength(env, pcm) < n * ch) return -1;
    if (av_frame_make_writable(e->frame) < 0) return -1;
    jfloat *in = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    if (!in) return -1;
    for (int c = 0; c < ch; c++) {
        float *plane = (float *) e->frame->extended_data[c];
        for (int i = 0; i < n; i++) plane[i] = in[i * ch + c];
    }
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, in, JNI_ABORT);
    if (avcodec_send_frame(e->ctx, e->frame) < 0) return -1;
    int r = avcodec_receive_packet(e->ctx, e->pkt);
    if (r == AVERROR(EAGAIN)) return 0;
    if (r < 0) return -1;
    int len = e->pkt->size;
    if (len > (*env)->GetArrayLength(env, out)) { av_packet_unref(e->pkt); return -1; }
    (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte *) e->pkt->data);
    av_packet_unref(e->pkt);
    return len;
}

JNIEXPORT void JNICALL
Java_com_streamvault_surround_Ac3Encoder_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    enc_free((Enc *) (intptr_t) handle);
}
