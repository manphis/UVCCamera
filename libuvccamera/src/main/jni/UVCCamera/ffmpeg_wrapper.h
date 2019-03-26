/*******************************************************************************
* All Rights Reserved, Copyright @ Quanta Computer Inc. 2013
* File Name: ffmpeg_wrapper.h
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
#ifndef __FFMPEG_WRAPPER_H__
#define __FFMPEG_WRAPPER_H__

#ifdef __cplusplus
extern "C" {
#endif

/* =============================================================================
                        Public function declarations
============================================================================= */
int ffmpeg_init(int src_width, int src_height);
int ffmpeg_fini();

int ffmpeg_open();
int ffmpeg_close();

int ffmpeg_decode(const unsigned char* src_data,
                  const unsigned int   src_len,
                  unsigned char*       dst_data,
                  unsigned int         dst_len);
int ffmpeg_scale(unsigned char*        src_data,
                 unsigned int          src_len,
                 unsigned char*        dst_data,
                 unsigned int          dst_len);
#ifdef __cplusplus
}
#endif

#endif /*  __FFMPEG_WRAPPER_H__ */
