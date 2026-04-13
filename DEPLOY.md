# 🚀 ClashProxy Deployment Guide

Harika! Docker image'ı başarıyla derlendi ("built"). Şimdi bu image'ı Hetzner sunucuna gönderme ve çalıştırma zamanı.

İşlemi senin için otomatize eden bir script hazırladım, ama manuel adımları da aşağıya ekledim.

## 📋 Ön Hazırlık

Hetzner sunucunun IP adresini ve giriş bilgilerini bildiğinden emin ol.
Örnek olarak:
- **IP:** `1.2.3.4` (bunu kendi IP'n ile değiştir)
- **User:** `root`

## Seçenek 1: Otomatik Script ile Deployment (Önerilen)

Senin için `scripts/deploy.sh` dosyasını hazırladım. Tek komutla yükleme yapar.

1. **Script'e çalıştırma izni ver:**
   ```bash
   chmod +x scripts/deploy.sh
   ```

2. **Script'i çalıştır:**
   *(1.2.3.4 yerine sunucu IP'ni yaz)*
   ```bash
   ./scripts/deploy.sh 1.2.3.4
   ```

   *Eğer kullanıcı `root` değilse:*
   ```bash
   ./scripts/deploy.sh 1.2.3.4 sunucu_kullanici_adi
   ```

Script şunları yapacak:
- Docker Image'ını zipleyip sunucuya gönderecek (`docker save` -> `docker load`).
- Eski çalışan `clashproxy` container'ı varsa durdurup silecek.
- Yeni versiyonu başlatacak (`restart: unless-stopped` modunda, yani sunucu kapanıp açılsa bile çalışır).

---

## Seçenek 2: Manuel Adımlar

Script kullanmak istemezsen, terminalden şu komutları sırasıyla çalıştırabilirsin:

1. **Image'ı Sunucuya Gönder:**
   ```bash
   docker save clashproxy:latest | ssh -C root@1.2.3.4 "docker load"
   ```

2. **Sunucuya Bağlan:**
   ```bash
   ssh root@1.2.3.4
   ```

3. **Eski Container'ı Temizle (Varsa):**
   ```bash
   docker stop clashproxy
   docker rm clashproxy
   ```

4. **Yeni Container'ı Başlat:**
   ```bash
   docker run -d \
     --name clashproxy \
     --restart unless-stopped \
     -p 8080:8080 \
     -e SPRING_PROFILES_ACTIVE=prod \
     clashproxy:latest
   ```

## ✅ Kontrol Etme

Deployment bittikten sonra tarayıcından veya curl ile test edebilirsin:

```bash
curl http://1.2.3.4:8080/actuator/health
```
*(Eğer actuator açıksa "UP" döner, yoksa ana sayfayı dene)*

Veya loglara bakmak için (sunucu içinde):
```bash
docker logs -f clashproxy
```
