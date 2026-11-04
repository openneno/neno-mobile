<h1 align="center">NENO for Android</h1>

<p align="center">
  <a href="https://github.com/openneno/neno">NENO</a> 卡片笔记的安卓客户端, 使用 Jetpack Compose 编写。<br />
  与网页端共用同一个 GitHub 笔记仓库, 两端可以同时使用、互相同步。
</p>

---

## 功能

- **记录**: 富文本编辑(加粗、斜体、下划线、删除线、列表、链接), `#标签` 自动补全, 添加多张图片, 对笔记进行批注
- **浏览**: 全部笔记、按标签 / 日期 / 关键词筛选, 长笔记自动折叠, 标签树与置顶标签, 标签 emoji 图标, 重命名标签
- **回顾**: 每日回顾(与网页端同一天看到同一批笔记)、那年今日、随机漫步
- **分享**: 把笔记生成图片卡片(纸笺、极光、夜空三种模板), 保存到相册或分享; 也可以从其他应用把文字分享到 NENO
- **同步**: 移植自网页端的同步引擎, 增量拉取、按笔记合并、一次提交推送; 前台近实时同步, 后台由 WorkManager 补推
- **设置同步**: 引导式填写 GitHub Token, 新建或选择仓库; 也可以扫描网页端的二维码或粘贴 neno token / 同步链接
- **外观**: 浅色 / 深色 / 跟随系统, 6 种主题色, 与网页端 "安静的纸" 设计一致
- **数据**: 导出 / 导入备份文件(与网页端格式相同), 写入同步到 Notion 的 GitHub Action

## 桌面组件

| 组件 | 说明 |
| --- | --- |
| 快速记录 | 一键打开编辑器写下想法, 宽度足够时附带每日回顾和随机漫步入口 |
| 随机漫步 | 在桌面显示一条过去的笔记, 点击刷新换一条, 点击内容打开详情; 遇到往年今天的笔记会显示 "那年今日" |
| 记录热力图 | 笔记数、标签数、记录天数和最近 12 周的热力图, 点击某一天查看当天的笔记 |

桌面组件使用 [Jetpack Glance](https://developer.android.com/develop/ui/compose/glance) 编写, 跟随应用的主题色, 笔记变化或同步后自动刷新。

## 与网页端的兼容

笔记仓库的结构与网页端完全相同:

```
YYYY-MM-DD/<笔记 id>.json   一条笔记
picData/<图片 id>.<后缀>     一张图片
pinTags/pinTags.json        标签设置(置顶标签、标签图标)
```

同步时通过 git blob sha 判断文件是否变化, 所以笔记文件的序列化与网页端的 `JSON.stringify(note, null, "\t")` 逐字节一致(保留键的顺序和其他工具写入的字段)。
`app/src/test` 中的测试用网页端代码生成的数据校验了这一点。安卓端新建的笔记 `source` 为 `android`。

## 构建

需要 JDK 17+ 和 Android SDK 36。

```bash
./gradlew assembleDebug        # 输出 app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 兼容性测试
```

技术栈: Kotlin · Jetpack Compose · Material 3 · Glance · WorkManager · OkHttp · [Compose Rich Editor](https://github.com/MohamedRejeb/compose-rich-editor) · jsoup · Coil · ZXing

## License

[GPL-3.0](LICENSE)
