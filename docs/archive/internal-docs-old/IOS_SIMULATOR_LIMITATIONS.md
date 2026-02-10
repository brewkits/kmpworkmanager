# iOS Simulator Limitations for Background Tasks

## ⚠️ BGTaskScheduler Không Hoạt Động Trong Simulator

### Vấn Đề

Khi chạy demo app trên iOS Simulator, bạn sẽ thấy lỗi:

```
❌ [TaskScheduler] Failed to submit task: The operation couldn't be completed. (BGTaskSchedulerErrorDomain error 1.)
```

### Giải Thích

**Đây KHÔNG phải là lỗi của code!** Đây là giới hạn của Apple:

- `BGTaskScheduler` **KHÔNG** hoạt động trong iOS Simulator
- `BGTaskScheduler` **CHỈ** hoạt động trên **thiết bị iOS thật**
- Error code 1 = `BGTaskSchedulerErrorCodeUnavailable` - nghĩa là service không available

### Tại Sao Apple Làm Vậy?

Apple design BGTaskScheduler để phụ thuộc vào:
- Trạng thái pin thực tế
- Kết nối mạng thực tế
- Thời gian sử dụng device thực tế
- Các điều kiện hệ thống thực tế (charging, idle, etc.)

Tất cả những điều kiện này không thể mô phỏng chính xác trong Simulator.

### Cách Test Background Tasks Trên Simulator

Apple cung cấp một cách **GIẢ LẬP** thông qua Xcode:

#### 1. Pause App trong Debugger

Khi app đang chạy, pause trong Xcode debugger, sau đó chạy command:

```bash
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"periodic-sync-task"]
```

#### 2. Sử dụng Breakpoint với Debug Command

1. Mở Xcode
2. Đặt breakpoint ở dòng submit task
3. Khi breakpoint hit, mở LLDB console
4. Chạy command:
   ```
   e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"your-task-id"]
   ```

### Test Trên Thiết Bị Thật

Để test **THẬT SỰ**, bạn phải:

1. **Build app lên thiết bị thật**
2. **Enable launch on background fetch**:
   ```bash
   # Trong Xcode scheme settings
   Debug > Execution > Wait for executable to be launched
   ```

3. **Trigger task manually**:
   ```bash
   # Connect device và chạy
   xcrun simctl launch booted dev.brewkits.kmpworkmanager

   # Trigger specific task
   e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"periodic-sync-task"]
   ```

4. **Hoặc đợi iOS tự động trigger** (có thể mất vài giờ)

### Cách Verify Code Đúng Trong Simulator

Mặc dù không test được background execution, bạn có thể verify:

#### ✅ Những gì CÓ THỂ test trong Simulator:

1. **Task Registration** - Xác nhận task ID được register
   ```swift
   // Log này xuất hiện là GOOD
   print("✅ [TaskScheduler] Task registered successfully")
   ```

2. **Validation Logic** - Task ID có trong Info.plist
   ```
   ℹ️ [TaskScheduler] Task ID validation passed
   ```

3. **Metadata Storage** - Task metadata được lưu đúng
   ```
   🔍 [TaskScheduler] Task metadata saved
   ```

4. **Worker Factory** - Worker được tạo đúng

5. **Business Logic** - Logic xử lý task

#### ❌ Những gì KHÔNG TEST được trong Simulator:

1. Background task execution
2. System-triggered scheduling
3. Task constraints (network, charging, battery)
4. Task timing và delays
5. BGTaskScheduler callbacks

### Log Bình Thường Trong Simulator

Khi chạy demo app trong Simulator, bạn sẽ thấy:

```
✅ OK - Task được enqueue
ℹ️ [TaskScheduler] Enqueue request - ID: 'one-time-upload'

✅ OK - Validation passed
ℹ️ [TaskScheduler] Scheduling one-time task

✅ OK - Request được tạo
🔍 [TaskScheduler] Creating BGAppRefreshTaskRequest

❌ EXPECTED - Submit failed vì Simulator
❌ [TaskScheduler] Failed to submit: BGTaskSchedulerErrorDomain error 1
```

### Kết Luận

**TẤT CẢ logic của KMP WorkManager đều ĐÚNG!**

Lỗi `BGTaskSchedulerErrorDomain error 1` trong Simulator là **EXPECTED BEHAVIOR**, không phải bug.

Để test thật sự:
- Build lên thiết bị iOS thật
- Dùng Xcode debug commands để simulate
- Hoặc đợi iOS tự trigger (có thể mất vài giờ)

---

## Tham Khảo

- [Apple: Debugging Background Tasks](https://developer.apple.com/documentation/backgroundtasks/starting_and_terminating_tasks_during_development)
- [Apple: BGTaskScheduler](https://developer.apple.com/documentation/backgroundtasks/bgtaskscheduler)
- [Apple WWDC: Advances in Background Execution](https://developer.apple.com/videos/play/wwdc2019/707/)
