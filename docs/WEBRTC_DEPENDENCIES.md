# WebRTC dependency record

| Dependency | Version | Origin | License | Native ABIs | SHA-256 |
|---|---|---|---|---|---|
| `io.github.webrtc-sdk:android` | `144.7559.05` | [webrtc-sdk/android](https://github.com/webrtc-sdk/android) | MIT | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` | `03d074e0e4e07866876f7d447b5ddb53caabefdcfae9f8ff95b15e2bff557a71` |

The artifact is used only for the staged WebRTC transport implementation. It
does not replace the Android application ID, signing certificate, device
identity, pairing records, or current LAN transport. No release key or TURN
credential is included in this repository.

OkHttp `5.3.0` is used only for the optional, user-configured WSS signaling
client. The endpoint is empty by default and the application does not deploy a
signaling or TURN server.
