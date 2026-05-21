Android registration snippet (Kotlin)

Add this to your Android app to register the phone's local callback URL with the relay when the app starts. Replace `relayHost` with the machine running the relay (e.g. `192.168.1.100:3000`).

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

fun registerWithRelay(relayHost: String, phonePort: Int = 8787, name: String = "My Phone") {
  val client = OkHttpClient()
  val url = "http://$relayHost/api/register"
  val callback = "http://" + /* your phone IP or use 0.0.0.0 + local discovery */ "<phone-ip>:$phonePort"
  val json = "{\"name\":\"$name\",\"url\":\"$callback\"}"
  CoroutineScope(Dispatchers.IO).launch {
    try {
      val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
      val req = Request.Builder().url(url).post(body).build()
      client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
          // handle error
        }
      }
    } catch (e: Exception) {
      // ignore or log
    }
  }
}
```

Notes:
- For reliability, your phone can re-register periodically or on network changes.
- The relay admin UI is at `http://<relay-host>:3000` by default; you can view/delete registered devices there.
