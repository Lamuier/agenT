# 全量代码审计（2026-10-10）

范围是当时工作区里的完整 agenT 源码。旧 `cursorT` 包已删除，新包为 `com.lamuier.agenT`。审查使用 Open Code Review 的委托预览，只审规则标出的可审文件。下文「处理」项已进入源码。2026-10-11 起，产品文档按当前清单、备份规则和链接白名单修订；本页仍是当日审计记录，不随后续功能改写结论。

## 覆盖

| 项 | 数量 |
| --- | --- |
| 变更文件 | 304 |
| 可审文件 | 183 |
| 已审 | 183 |
| 跳过 | 0 |
| 覆盖率 | 100% |
| 规则排除 | 121 |

排除原因：旧包删除 51、测试路径 51、Markdown 16、二进制 3。测试不在可审清单里，本报告不评价测试是否充分。

凭据、网络、通知、小组件和重置身份按文件看过。资源 XML、Gradle 和预览脚本核对了导出组件、明文流量、备份和密钥。`!!` 都在前面的空判断之后。版本号 `1.0.0` 与变更记录里「新安装包 ID 首发」一致。

Keystore 的 AES-GCM 与 AAD、关闭备份、禁止明文 HTTP、不跟随重定向、响应体 1MB 上限、查看 Token 前的设备解锁，以及公共重置链接白名单，这几处没有发现问题。

## 高

### 1. Cursor 账号 JSON 会被后写入盖掉

`EncryptedAccountStore` 原来的锁是每个实例一把。界面、小组件和常驻通知各自创建 `CursorRepository`，并发执行「读出整份账号 JSON，再整份写回」。后完成的提交会盖掉先完成的新 Token，或把已删除的账号写回去。

**处理：** 锁改到存储类的伴生对象，同一进程内的读写共用这一把锁。验收用的独立偏好文件不走正式数据的迁移。

### 2. Debug 验收入口可被其他应用调起，并会清掉本机 Codex 凭据

`UiReviewActivity` 原先 `exported="true"`，且没有权限。任意应用都能向已安装的 debug 包发 Intent。进入页面会改掉语言、时区和主题。`mode=storage-check` 的 `finally` 会无条件 `clearCredential()`，即使用户已有账号、前面的写入抛错，也会删掉真实 Codex Token 和全部本机重置记录。

**处理：**

- 清单改为 `exported="false"`。adb 仍可启动，其他应用不行。
- 凭据检查使用 `agentt_qa_*` 偏好和独立 Keystore 别名，结束时删除这些别名。
- 语言、时区和主题先记在 `agentt_ui_review_hold_v1`。验收页结束，或下次进程启动时，写回原来的值。

### 3. 重置身份在 JWT 缺填充时会对错人

ChatGPT / JWT 的 payload 通常是不补 `=` 的 base64url。过期判断会先补齐，重置身份原来直接解码。Android API 26 的 `java.util.Base64` 会拒绝未补齐的输入。异常被吃掉后，身份退回「整段 token」的哈希。更换 Token 后，同一用户的重置记录还在旧 key 下，界面读到空账本。

**处理：** `TokenUtils.jwtPayload` 统一补齐后再解码。重置身份和 Codex 过期判断都走这一处。单元测试覆盖了 payload 长度不能被 4 整除的无填充 JWT。

## 中

### 4. 公共重置 ETag 会原样写回请求头

响应里的 `ETag` 只限制了长度，就存下来放进下次的 `If-None-Match`。值里若有回车或换行，`setRequestProperty` 会抛异常，而且这个异常不在分页解析的捕获里。坏 ETag 会留在本地，之后每次拉取都在发请求前失败。

**处理：** 只接受长度 1–512、不含控制字符的 ETag。已存的非法值会删掉，不再送出。

### 5. Codex Token 校验失败时，英文界面会看到中文硬编码

`CodexTokenValidator.requireValid` 抛出的是写死的中文。界面把 `IllegalArgumentException.message` 直接显示出来。Cursor 路径用的是字符串资源。

**处理：** 校验改为读取 `error_token_length` / `error_token_unsafe`，跟随当前界面语言。

### 6. 公共重置缓存写入不检查 `commit()` 结果

`commit()` 失败时仍把内存快照当成成功返回。下次冷启动又回到旧缓存，界面却显示已经更新。

**处理：** `commit()` 失败时抛错，文案为 `reset_public_cache_error`，已有缓存保留。

## 未改

规则排除的测试、已删除的 `cursorT` 源码、Markdown 和二进制资源没有逐份审。小组件任务在系统 `onStopJob` 时不调用 `jobFinished`，这是 JobScheduler 的正常约定，没有改。
