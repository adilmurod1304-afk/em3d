# ChildGuard — yuz orqali bola ekran vaqtini cheklovchi Android ilova

Old kamera orqali kim telefondan foydalanayotganini aniqlaydi. **Bir nechta
bola** profili qo'llab-quvvatlanadi — har biri alohida ism va yuz namunalariga,
hamda **alohida kunlik vaqt hisobiga** ega. Agar biror **bola** yuzi aniqlanib,
o'sha bolaning foydalanishi belgilangan vaqtdan (masalan **30 daqiqa**) oshsa —
telefon **bloklanadi**. Blokni faqat **ota-onaning yuzi** (yoki zaxira **PIN**)
ochadi. Blok ochilganda faqat o'sha bolaning hisobi nolga tushadi.

> ⚠️ Bu **prototip / namuna loyiha**. Haqiqiy qurilmada sinab, aniqlikni sozlash
> kerak. Tayyor APK emas — Android Studio'da yig'iladi.

---

## Qanday ishlaydi

1. **Ro'yxatga olish** — ota-ona va har bir bola yuzi bir necha marta kameraga
   olinadi. Har bir yuz TFLite model yordamida son vektoriga (embedding)
   aylantiriladi. Bolalar sonida cheklov yo'q.
2. **Kuzatuv** — fon xizmat (`MonitorService`) har 5 soniyada old kameradan kadr
   oladi va kim ekanini (ota-ona yoki aynan qaysi bola) aniqlaydi.
3. **Hisob** — aniqlangan bolaning soniyalari **o'sha bolaning** hisobiga
   qo'shiladi (ota-ona yoki noma'lum odam hech kimning vaqtini oshirmaydi).
   Har bir bola uchun alohida hisob yuritiladi va har kuni avtomatik nolga tushadi.
4. **Blok** — biror bolaning vaqti chegaradan oshsa, `LockActivity` butun ekranni
   egallaydi va qaysi bola ekanini ko'rsatadi. Faqat ota-ona yuzi ketma-ket
   2 marta tasdiqlansa yoki to'g'ri PIN kiritilsa, blok ochiladi va **o'sha
   bolaning** hisobi qaytadan boshlanadi.

## Komponentlar

| Fayl | Vazifasi |
|------|----------|
| `MainActivity` | Ruxsatlar, bolalar ro'yxati (qo'shish/o'chirish), yuz qo'shish, vaqt/PIN, himoyani yoqish |
| `EnrollActivity` | Ota-ona yoki aniq bir bola yuzini namuna sifatida qo'shish |
| `FaceRecognizer` | ML Kit (yuzni topish) + TFLite (embedding) + kim ekanini aniqlash |
| `MonitorService` | Fon kamera kuzatuvi, har bir bola vaqti hisobi, blokni ishga tushirish |
| `LockActivity` | Blok ekrani — ota-ona yuzi yoki PIN bilan ochiladi |
| `Profiles` | Ota-ona va bir nechta bola profillari (ism + yuz namunalari) |
| `Prefs` | Sozlamalar va har bir bola uchun kunlik vaqt hisobi |

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
4. **Bola qo'shish** → ism kiriting → o'sha bola uchun 3–5 marta yuz namunasi oling.
   Kerak bo'lsa yana bola qo'shing (nechta bo'lsa ham).
5. Har bir bola uchun kunlik vaqt chegarasini (daqiqa) va zaxira **PIN** ni saqlang.
6. **Himoyani yoqish** tugmasini bosing.

> Har bir bola qatorida bugungi foydalanish (masalan `12/30 daq`), namunalar
> soni ko'rsatiladi; "Yuz qo'shish" va "O'chirish" tugmalari bor.

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
