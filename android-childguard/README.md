# ChildGuard — yuz orqali bola ekran vaqtini cheklovchi Android ilova

Old kamera orqali kim telefondan foydalanayotganini aniqlaydi. Agar **bola**
yuzi aniqlanib, uning foydalanishi belgilangan vaqtdan (masalan **30 daqiqa**)
oshsa — telefon **bloklanadi**. Blokni faqat **ota-onaning yuzi** (yoki zaxira
**PIN**) ochadi.

> ⚠️ Bu **prototip / namuna loyiha**. Haqiqiy qurilmada sinab, aniqlikni sozlash
> kerak. Tayyor APK emas — Android Studio'da yig'iladi.

---

## Qanday ishlaydi

1. **Ro'yxatga olish** — ota-ona va bola yuzlari bir necha marta kameraga olinadi.
   Har bir yuz TFLite model yordamida son vektoriga (embedding) aylantiriladi.
2. **Kuzatuv** — fon xizmat (`MonitorService`) har 5 soniyada old kameradan kadr
   oladi va kim ekanini aniqlaydi.
3. **Hisob** — faqat **bola** aniqlanган soniyalar to'planadi (ota-ona yoki
   noma'lum odam vaqtni oshirmaydi). Har kuni yarim tunda nolga tushadi.
4. **Blok** — to'plangan vaqt chegaradan oshsa, `LockActivity` butun ekranni
   egallaydi. Faqat ota-ona yuzi ketma-ket 2 marta tasdiqlansa yoki to'g'ri PIN
   kiritilsa, blok ochiladi va hisob qaytadan boshlanadi.

## Komponentlar

| Fayl | Vazifasi |
|------|----------|
| `MainActivity` | Ruxsatlar, yuzlarni qo'shish, vaqt/PIN sozlash, himoyani yoqish |
| `EnrollActivity` | Ota-ona/bola yuzini namuna sifatida qo'shish |
| `FaceRecognizer` | ML Kit (yuzni topish) + TFLite (embedding) + solishtirish |
| `MonitorService` | Fon kamera kuzatuvi, vaqt hisobi, blokni ishga tushirish |
| `LockActivity` | Blok ekrani — ota-ona yuzi yoki PIN bilan ochiladi |
| `EmbeddingStore` / `Prefs` | Yuzlar va sozlamalarni saqlash |

## Yig'ish (build)

1. **Android Studio** (Koala / 2024+) o'rnating.
2. Shu `android-childguard/` papkasini Android Studio'da oching.
3. Internet bo'lsa Gradle kutubxonalarni o'zi yuklaydi.
4. **Yuz modelini qo'shing**: `app/src/main/assets/mobile_face_net.tflite`
   (qarang: `assets/PUT_MODEL_HERE.txt`).
5. `Build > Build APK(s)` yoki telefonni ulab **Run** ▶ bosing.

Terminaldan (SDK o'rnatilgan bo'lsa):
```
./gradlew assembleDebug
# natija: app/build/outputs/apk/debug/app-debug.apk
```

## Telefonda sozlash

1. Ilovani oching → **1-tugma** bilan kamera/bildirishnoma ruxsatini bering.
2. **2-tugma** bilan "ustidan ko'rinish" (overlay) ruxsatini yoqing.
3. **Ota-ona yuzini qo'shish** → 3–5 marta "Namuna qo'shish" (har xil burchak/yorug'lik).
4. **Bola yuzini qo'shish** → xuddi shunday.
5. Vaqt chegarasini (daqiqa) va zaxira **PIN** ni saqlang.
6. **Himoyani yoqish** tugmasini bosing.

## Muhim cheklovlar / ogohlantirish

- **Aniqlik:** yuz tanish yorug'lik, burchak va modelга bog'liq. `Prefs`dagi
  `match_threshold` ni sozlang (kichik = qattiqroq). Har bir shaxs uchun bir
  necha namuna qo'shish aniqlikni oshiradi.
- **Chetlab o'tish:** overlay/activity asosidagi blokni ilg'or foydalanuvchi
  (xavfsiz rejim, ilovani majburan to'xtatish) chetlab o'tishi mumkin. Kuchliroq
  blok uchun Device Admin (`AdminReceiver`) yoki Accessibility xizmatini
  kengaytirish kerak.
- **Batareya:** doimiy kamera kuzatuvi quvvat sarflaydi. `TICK_MS` (hozir 5s) ni
  kattalashtirib tejash mumkin.
- **Maxfiylik va qonun:** bu ilova faqat **o'z farzandingiz** qurilmasida,
  ularning holatiga mos ravishda ishlatilishi uchun. Yuz ma'lumotlari faqat
  telefon ichida (SharedPreferences) saqlanadi, hech qayerga yuborilmaydi.
  Boshqa insonni uning xabarisiz kuzatish ko'p mamlakatlarda qonunga ziddir.
- **Bola yoshi:** haqiqiy mahsulot uchun bolalar ma'lumotlari bo'yicha
  qoidalarga (masalan COPPA/GDPR-K) rioya qilish kerak.

## Keyingi qadamlar (takomillashtirish g'oyalari)

- Device Admin `lockNow()` bilan tizim qulfidan foydalanish.
- Accessibility xizmati orqali ilovalarni tanlab bloklash.
- Bir nechta bola profili, kunlik hisobot, masofaviy boshqaruv.
- Yuzni "liveness" (tirik yuz) tekshiruvi — fotosurat bilan aldashning oldini olish.
