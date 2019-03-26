/*******************************************************************************
* All Rights Reserved, Copyright @ Quanta Computer Inc. 2013
* File Name: avcodec_wrapper.c
* File Mark:
* Content Description:
* Other Description: None
* Version: 1.0
* Author: Paul Weng
* Date: 2013/12/11
*
* History:
*   1.2013/12/11 Paul Weng: draft
*******************************************************************************/
/* =============================================================================
                                Included headers
============================================================================= */
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <pthread.h>
#include <android/log.h>

#include "libavcodec/avcodec.h"
#include "libavutil/opt.h"
#include "libavutil/common.h"
#include "libswscale/swscale.h"


#define TAG "camera_ffmpeg"
#ifdef __ANDROID__
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG  , TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO   , TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN   , TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR  , TAG, __VA_ARGS__)
#endif

/* =============================================================================
                                Marco
============================================================================= */
#define LOCK()    pthread_mutex_lock(&lock)
#define UNLOCK()  pthread_mutex_unlock(&lock)
#define SCALE_LOCK()    pthread_mutex_lock(&lock2)
#define SCALE_UNLOCK()  pthread_mutex_unlock(&lock2)

/* =============================================================================
                        Public function declarations
============================================================================= */
int ffmpeg_init(int src_width, int src_height);
int ffmpeg_fini(void);
int ffmpeg_open(void);
int ffmpeg_close(void);
int ffmpeg_decode(const unsigned char* src_data,
                  const unsigned int   src_len,
                  unsigned char*       dst_data,
                  unsigned int         dst_len);

/* =============================================================================
                                Private Data
============================================================================= */
static AVCodec*             codec = NULL;
static AVCodecContext*      ctx = NULL;
static AVFrame*             frame = NULL;
static AVFrame*             frame_rgb = NULL;
static struct SwsContext*   sws_ctx = NULL;
static unsigned char*       buffer = NULL;
static pthread_mutex_t      lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t      lock2 = PTHREAD_MUTEX_INITIALIZER;
static int g_width = 0, g_height = 0;

static int count;


int ffmpeg_init(int src_width, int src_height)
{
    int          ret = 0;

    LOCK();
    avcodec_register_all();

    LOGE("%s width = %d height = %d", __FUNCTION__, src_width, src_height);

    codec = avcodec_find_decoder(AV_CODEC_ID_MJPEG);
    if (NULL == codec) {
        LOGE("%s avcodec_find_decoder failure", __FUNCTION__);
        UNLOCK();
        return -1;
    } else {
        LOGE("%s avcodec_find_decoder success", __FUNCTION__);
    }

    ctx = avcodec_alloc_context3(codec);
    if (NULL == ctx) {
        LOGE("%s avcodec_alloc_context3 failure", __FUNCTION__);
        UNLOCK();
        return -1;
    } else {
        LOGE("%s avcodec_alloc_context3 success", __FUNCTION__);
    }

    ctx->width = src_width;
    ctx->height = src_height;
    ctx->pix_fmt = AV_PIX_FMT_YUV422P;
    ctx->err_recognition |= AV_EF_EXPLODE;

    g_width = src_width;
    g_height = src_height;

    frame = av_frame_alloc();
    if (NULL == frame) {
        LOGE("%s av_frame_alloc failure", __FUNCTION__);
        UNLOCK();
        return -1;
    } else {
        LOGE("%s av_frame_alloc success", __FUNCTION__);
    }

    buffer = (unsigned char*)malloc(src_width*src_height*sizeof(short));
    if (NULL == buffer) {
    	LOGE("%s malloc buffer failure", __FUNCTION__);
        UNLOCK();
    	return -1;
    }

    UNLOCK();

    SCALE_LOCK();

    sws_ctx = sws_getContext(src_width, src_height, AV_PIX_FMT_YUV422P,    //AV_PIX_FMT_YUVJ444P
                            src_width, src_height, AV_PIX_FMT_NV12,
                            SWS_FAST_BILINEAR, NULL, NULL, NULL);

    if (NULL == sws_ctx) {
        LOGE("%s sws_getContext failure", __FUNCTION__);
        UNLOCK();
        return -1;
    } else {
        LOGE("%s sws_getContext success", __FUNCTION__);
    }

    frame_rgb = av_frame_alloc();
    if (NULL == frame_rgb) {
        LOGE("%s av_frame_alloc failure", __FUNCTION__);
        UNLOCK();
        return -1;
    } else {
        LOGE("%s av_frame_alloc success", __FUNCTION__);
    }
    SCALE_UNLOCK();

    count = 0;

    return ret;
}


int ffmpeg_fini()
{
    LOCK();
    if (frame) {
        av_frame_free(&frame);
    }

    if (ctx) {
        LOGE("%s av_free success", __FUNCTION__);
        av_free(ctx);
    }

    if (buffer) {
        free(buffer);
    }

    codec = NULL;
    ctx = NULL;
    frame = NULL;
    buffer = NULL;
    UNLOCK();

    SCALE_LOCK();
    if (frame_rgb) {
        av_frame_free(&frame_rgb);
    }

    if (sws_ctx) {
        LOGE("%s sws_freeContext success", __FUNCTION__);
        sws_freeContext(sws_ctx);
    }

    sws_ctx = NULL;
    frame_rgb = NULL;
    g_width = 0;
    g_height = 0;
    SCALE_UNLOCK();

    return 0;
}


int ffmpeg_open()
{
    int ret = 0;

    LOCK();
    if (ctx) {
        ret = avcodec_open2(ctx, codec, NULL);
        LOGE("%s avcodec_open2 ret = %d", __FUNCTION__, ret);
    } else {
        UNLOCK();
        return -1;
    }
    UNLOCK();

    return ret;
}


int ffmpeg_close()
{
    LOCK();
    if (ctx) {
        LOGE("%s avcodec_close success", __FUNCTION__);
        avcodec_close(ctx);
    }
    UNLOCK();

    return 0;
}


int ffmpeg_decode(const unsigned char* src_data,
                  const unsigned int   src_len,
                  unsigned char*       dst_data,
                  unsigned int         dst_len)
{
    AVPacket    pkt;
    int         len = 0;
    int         got_frame = 0;
    int         ret = 0;
    int         nalu_type = 0;
    int         start_index = 0;
    int idx = 0;

    LOCK();
    if (ctx) {
        avpicture_fill((AVPicture*)frame, buffer, AV_PIX_FMT_YUV422P, ctx->width, ctx->height);
//        avpicture_fill((AVPicture*)frame_rgb, dst_data, AV_PIX_FMT_YUV420P, ctx->width, ctx->height);
//        avpicture_fill((AVPicture*)frame_rgb, dst_data, AV_PIX_FMT_NV12, ctx->width, ctx->height);

        av_init_packet(&pkt);

        pkt.data = (unsigned char*)src_data;
        pkt.size = src_len;

        len = avcodec_decode_video2(ctx, frame, &got_frame, &pkt);

        if (got_frame && (len == src_len)) {
            len = avpicture_layout((AVPicture *)frame, ctx->pix_fmt, ctx->width, ctx->height, dst_data, dst_len);
        } else {
            LOGE("%s camera avcodec_decode_video2 failure len = %d, src_len = %d got_frame = %d",
            __FUNCTION__, len, src_len, got_frame);

            ret = -1;
        }

    } else {
    	ret = -2;
    }
    UNLOCK();

    return ret;
}

int ffmpeg_scale(unsigned char*  src_data,
                 unsigned int    src_len,
                 unsigned char*  dst_data,
                 unsigned int    dst_len)
{
    int         len = 0;
    int         ret = 0;

    uint8_t *inbuf[4];
    int inlinesize[4] = {g_width, g_width/2, g_width/2, 0};

    SCALE_LOCK();
    if (sws_ctx) {
        inbuf[0] = src_data;
        inbuf[1] = src_data + g_width*g_height;
        inbuf[2] = src_data + g_width*g_height + g_width*g_height/2;
        inbuf[3] = NULL;
        inlinesize[0] = g_width;
        inlinesize[1] = g_width/2;
        inlinesize[2] = g_width/2;

        avpicture_fill((AVPicture*)frame_rgb, dst_data, AV_PIX_FMT_NV12, g_width, g_height);

        len = sws_scale(sws_ctx, (uint8_t const* const*)inbuf,
                                    inlinesize,  0, g_height, frame_rgb->data,
                                    frame_rgb->linesize);

    } else {
    	ret = -2;
    }
    SCALE_UNLOCK();
    return ret;
}

