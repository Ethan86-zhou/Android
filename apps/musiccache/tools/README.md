# 网页缓存设备自测

这些工具验证 **真实 Android WebView → 自建音频缓存**。测试音由本机生成，不含第三方歌曲；通过不代表 QQ 音乐登录、授权、真实歌曲播放已经通过。

先使用本 APP 的 Wrapper 构建 debug APK，再在 APP 根目录执行：

```powershell
python tools/device_acceptance.py --apk app/build/outputs/apk/debug/app-debug.apk --install --output outputs/<新的日期版本目录>/device
```

如连接多台手机，加 `--serial <设备>`；如 ADB 不在 PATH 或常见 SDK 目录，加 `--adb <本机adb路径>`。`--install` 仅覆盖安装并保留数据；Android 安装更新时可能结束旧版应用，所以正常进程 PID 基线在安装之后记录。省略时要求手机已安装完全相同 SHA-256 的 APK。签名不匹配会停止，不会卸载。

脚本会：

1. 记录 APK 哈希、Git 基线提交和逐文件源码哈希，确保结果对应本次构建。
2. 使用随机命名的独立 WebView profile 和自测缓存目录，启动仅限 debug 的 `SelfTestActivity` 和独立 `:cachetest` 进程。正常 `MainActivity` 进程不动。
3. 通过本机 loopback 服务与 ADB reverse 提供自产 2 秒音频，验证两个 Range 响应完整拼接、部分缓存、`no-store`、提前断流。Range 的 `Content-Length` 必须是与正文长度一致的单值；源端忽略 Range 时，WebView 必须收到可读的 502 空正文。
   同一资源的新签名链接先在当前 URL 进行 `Range: bytes=0-0` 认证；强 ETag、总长与 MIME 一致时，只传 1 字节校验数据、整首命中磁盘且音频磁盘量不增加。页面逐步读取原生只读快照，再与源端请求日志交叉核对。校验流量与普通音频网络流量分开统计。不同 ETag 的测试音必须下载实际新内容，403 拒绝的签名不得使用旧音频。
4. 在线阶段另外用独立小容量目录运行真实缓存引擎的容量探针，检查满容量淘汰、别名清理及重启后字节正确，结果保存为 `capacity.json`；不对用户音频目录施加容量压力。等待自测进程在报告后自行退出，关闭测试服务器，再启动独立自测进程的离线阶段，核对整首 SHA-256、任意 Range 和 `<audio>` 播放至 `ended`。
5. 自测进程再次自行退出后，启动下一阶段，验证缺失片段、未完整缓存的整首、`no-store`、截断响应、源端忽略 Range、未经在线验证的新签名或被 403 拒绝的签名都无法伪装为离线命中。已经成功验证的新签名可以在重启后复用同一份音频。每项失败测试必须读完响应正文并确认 HTTP 504；带 Range 的错误响应必须为空且单值 `Content-Length: 0`，无 Range 时允许完整错误正文但长度头必须是与实读字节一致的单值。网络错误、超时或无法读取正文都算失败。
6. 保存各阶段报告；前后确认前台仍为 `SelfTestActivity` 才保留截图。移除本脚本创建的反向端口，对照正常进程 PID。正常 APP 不重新打开、不重置。

手机 Wi-Fi/移动数据、Chrome 和正常 APP 的账号数据不改动。脚本不主动结束任何进程，不清空数据、不卸载、不读取 Cookie。若自测进程未能在 15 秒内自行退出，验收停止并报告，不尝试替代终止操作。自测文件仅含自产音频和调试报告；旧验证目录不会覆盖。手机锁屏或其它 APP 遮挡时跳过截图，避免保存登录页或其它界面。

结果见 `验证结果.txt`、`verification.json`、各阶段 JSON、`fixture-requests.jsonl` 和 `source-sha256.json`。`source-sha256.json` 记录参与验收的工作区版本；存在未提交改动时，Git 基线提交不能单独代表实际源码。自测模式报告的原生缓存字节、缺段次数和条目完整度必须与页面测得结果一致，只有网页“通过”不能算整体验收成功。

主机夹具回归不连接手机：

```powershell
python -m unittest discover -s tools -p 'test_*.py' -v
node --test tools/test_fixture_page.mjs
```

HTTP Range 语义参考 [MDN HTTP range requests](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Range_requests)；媒体完成状态参考 [MDN audio element](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/audio)。
