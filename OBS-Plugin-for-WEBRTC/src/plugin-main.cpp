#include <obs-module.h>
#include "WebRTCSource.h"

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("obs-plugin-webrtc", "en-US")

static const char *webrtc_source_get_name(void *unused)
{
    UNUSED_PARAMETER(unused);
    return "WebRTC Source";
}

static obs_source_info webrtc_source_info = {
    .id = "webrtc_source",
    .type = OBS_SOURCE_TYPE_INPUT,
    .output_flags = OBS_SOURCE_VIDEO,
    .get_name = webrtc_source_get_name,
    .create = (obs_source_create_t)webrtc_source_create,
    .destroy = webrtc_source_destroy,
    .update = webrtc_source_update,
    .get_properties = webrtc_source_properties,
    .get_width = webrtc_source_get_width_height,
    .get_height = webrtc_source_get_width_height,
    .video_render = webrtc_source_render,
};

bool obs_module_load(void)
{
    obs_register_source(&webrtc_source_info);
    blog(LOG_INFO, "WebRTC Source plugin loaded");
    return true;
}

void obs_module_unload(void)
{
    blog(LOG_INFO, "WebRTC Source plugin unloaded");
}
