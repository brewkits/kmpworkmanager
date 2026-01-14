# ❗ QUAN TRỌNG: klibs.io KHÔNG phải nơi upload thủ công

## klibs.io là gì?

**klibs.io** là một **search engine/discovery service** do JetBrains tạo ra để giúp developers tìm kiếm Kotlin Multiplatform libraries dễ dàng hơn.

🔍 **Nó KHÔNG phải là nơi để upload library!**

## Làm sao để library xuất hiện trên klibs.io?

Library của bạn sẽ **TỰ ĐỘNG** xuất hiện trên klibs.io nếu đáp ứng 2 điều kiện:

### ✅ Điều kiện 1: Có trên GitHub
- Repository phải **public** và **open source**
- URL: https://github.com/brewkits/kmpworkmanager ✓

### ✅ Điều kiện 2: Publish lên Maven Central
- **Ít nhất 1 artifact** phải được publish lên Maven Central
- Group ID: `io.brewkits`
- Artifact ID: `kmpworkmanager`

### ⏱️ Thời gian index: 24 giờ

Sau khi publish lên Maven Central, đợi tối đa **24 giờ** để klibs.io tự động index.

---

## Workflow để xuất hiện trên klibs.io

```
1. Tạo JIRA ticket cho io.brewkits (Đang chờ approve)
   ↓
2. Setup signing credentials
   ↓
3. Publish lên Maven Central
   ↓
4. Đợi 24h → TỰ ĐỘNG xuất hiện trên klibs.io ✅
```

---

## Tại sao cần Maven Central?

klibs.io crawl data từ:
- 📦 **Maven Central** - để lấy thông tin artifacts, versions
- 🐙 **GitHub** - để lấy README, stars, description

**Không thể upload trực tiếp lên klibs.io!**

---

## Nếu library không xuất hiện sau 24h?

1. Kiểm tra library đã publish lên Maven Central chưa:
   - https://central.sonatype.com/search?q=io.brewkits.kmpworkmanager

2. Kiểm tra repository GitHub có public không:
   - https://github.com/brewkits/kmpworkmanager

3. Report issue nếu vẫn không thấy:
   - https://github.com/JetBrains/klibs-io-issue-management/issues

---

## Tham khảo

- [Introducing klibs.io](https://blog.jetbrains.com/kotlin/2024/12/introducing-klibs-io-a-new-way-to-discover-kotlin-multiplatform-libraries/)
- [klibs.io FAQ](https://klibs.io/faq)
- [Issue Tracker](https://github.com/JetBrains/klibs-io-issue-management)

