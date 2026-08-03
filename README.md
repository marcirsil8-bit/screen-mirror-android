# 📱📺 Screen Mirror — Celular → TV Box

Dois apps nativos Android para espelhamento de tela + áudio com baixa latência via rede WiFi local.

## Apps

### 1. Sender (Celular) — `screenmirror/sender/`
- Captura a tela via **MediaProjection API**
- Captura áudio via **AudioRecord**
- Codifica vídeo em **H.264** (MediaCodec) a 60fps, 8Mbps
- Codifica áudio em **AAC** 44.1kHz estéreo, 128kbps
- Transmite via **UDP** na rede local (broadcast)
- Descoberta automática do receptor

### 2. Receiver (TV Box) — `screenmirror/receiver/`
- Descobre o transmissor via **UDP broadcast**
- Recebe stream de vídeo H.264 e decodifica via **MediaCodec**
- Renderiza em **SurfaceView** (fullscreen, landscape)
- Recebe stream de áudio AAC, decodifica e toca via **AudioTrack**
- Modo fullscreen otimizado para TV

## Como funciona

```
Celular (Sender)                          TV Box (Receiver)
┌─────────────┐    UDP Broadcast         ┌─────────────┐
│ MediaProj   │ ──────────────────────── │ DatagramSocket│
│ Screen+Audio│  Port 50001 (vídeo)     │  Video Decoder │
│ H.264 + AAC │  Port 50002 (áudio)     │  Audio Decoder │
│ UDP Stream  │  Port 50000 (discovery) │  SurfaceView   │
└─────────────┘                         └─────────────┘
```

## Compilando

### Pré-requisitos
- Android SDK (API 26+)
- JDK 17
- Gradle 8.1+ (ou usar o wrapper)

### Build
```bash
cd screenmirror
./build.sh
```

Ou manualmente:
```bash
# Sender
cd sender && ./gradlew assembleDebug

# Receiver  
cd receiver && ./gradlew assembleDebug
```

Os APKs serão gerados em:
- `sender/app/build/outputs/apk/debug/app-debug.apk`
- `receiver/app/build/outputs/apk/debug/app-debug.apk`

## Instalação

1. Instale o **Sender** no celular
2. Instale o **Receiver** na TV Box
3. Conecte ambos na **mesma rede WiFi**
4. Abra o Receiver na TV e toque em "📡 Aguardar"
5. Abra o Sender no celular e toque em "📡 Iniciar Transmissão"
6. Autorize a captura de tela

## Otimizações de latência

- **UDP** em vez de TCP (sem retransmissão)
- **MediaCodec** hardware (codificação/decodificação nativa)
- **Low latency mode** no MediaCodec (Android 11+)
- **Zero B-frames** para reduzir buffer
- **I-frame a cada 2s** para recuperação rápida
- **Queue com drop** de frames antigos se achar atraso
- **8Mbps** de bitrate para qualidade sem travamento
- **60fps** para fluidez máxima

## Configurações de rede

| Porta | Uso |
|-------|-----|
| 50000 | Descoberta (UDP broadcast) |
| 50001 | Stream de vídeo (UDP) |
| 50002 | Stream de áudio (UDP) |

## Permissões necessárias

**Sender:**
- INTERNET, ACCESS_WIFI_STATE (rede)
- RECORD_AUDIO (captura de áudio)
- FOREGROUND_SERVICE_MEDIA_PROJECTION (captura de tela)

**Receiver:**
- INTERNET, ACCESS_WIFI_STATE (rede)
- MODIFY_AUDIO_SETTINGS (playback de áudio)

## Compatibilidade

- Android 8.0+ (API 26)
- Funciona com qualquer TV Box Android
- Otimizado para ARM e x86
