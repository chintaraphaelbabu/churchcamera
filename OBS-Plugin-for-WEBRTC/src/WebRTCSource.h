#pragma once

#include <obs-module.h>

struct WebRTCSource
{
    obs_source_t *source;
    obs_source_frame *frame;
    uint32_t width;
    uint32_t height;
    char *bridge_url;
    bool active;
};

WebRTCSource *webrtc_source_create(obs_data_t *settings, obs_source_t *source);
void webrtc_source_destroy(void *data);
void webrtc_source_update(void *data, obs_data_t *settings);
obs_properties_t *webrtc_source_properties(void *data);
void webrtc_source_get_width_height(void *data, uint32_t *width, uint32_t *height);
void webrtc_source_render(void *data, gs_effect_t *effect);
