#include "WebRTCSource.h"
#include <cstring>

static const char *bridge_url_default = "http://192.168.1.24:8787/obs-bridge";

WebRTCSource *webrtc_source_create(obs_data_t *settings, obs_source_t *source)
{
    auto *context = static_cast<WebRTCSource *>(bzalloc(sizeof(WebRTCSource)));
    context->source = source;
    context->width = 1280;
    context->height = 720;
    context->active = false;
    const char *url = obs_data_get_string(settings, "bridge_url");
    context->bridge_url = bstrdup(url && url[0] ? url : bridge_url_default);
    return context;
}

void webrtc_source_destroy(void *data)
{
    auto *context = static_cast<WebRTCSource *>(data);
    if (!context)
    {
        return;
    }
    bfree(context->bridge_url);
    bfree(context);
}

void webrtc_source_update(void *data, obs_data_t *settings)
{
    auto *context = static_cast<WebRTCSource *>(data);
    const char *url = obs_data_get_string(settings, "bridge_url");
    if (url)
    {
        bfree(context->bridge_url);
        context->bridge_url = bstrdup(url);
    }
}

obs_properties_t *webrtc_source_properties(void *data)
{
    obs_properties_t *props = obs_properties_create();
    obs_properties_add_text(props, "bridge_url", "WebRTC Bridge URL", OBS_TEXT_DEFAULT);
    return props;
}

void webrtc_source_get_width_height(void *data, uint32_t *width, uint32_t *height)
{
    auto *context = static_cast<WebRTCSource *>(data);
    *width = context->width;
    *height = context->height;
}

void webrtc_source_render(void *data, gs_effect_t *effect)
{
    auto *context = static_cast<WebRTCSource *>(data);
    if (!context || !context->active)
    {
        return;
    }

    // Placeholder: actual video texture rendering must be implemented here.
    // For now, this source does not render a decoded WebRTC frame.
}
