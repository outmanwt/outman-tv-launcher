# 第三方许可与来源说明

本项目原创部分采用仓库根目录的 MIT License。第三方参考代码、运行依赖与构建工具保留各自的版权和许可；本文件不把第三方材料重新许可为本项目的 MIT。

## mytv-android：频道归组逻辑参考

- 上游项目：`minyoad/mytv-android`
- 参考提交：`9263a658b373f573410adeed135b2a6c49bbc1f7`
- 参考范围：同组、同名频道的多线路归组思路与相关逻辑。
- 上游许可：MIT。
- 上游版权声明：`Copyright (c) 2020-PRESENT yaoxieyoulei`。
- 工程内保留的许可文本：`src/app/src/main/assets/licenses/mytv-android-MIT.txt`。

以下保留该上游 MIT 许可文本：

```text
MIT License

Copyright (c) 2020-PRESENT yaoxieyoulei

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## cleanyourtv：仅设计参考

`cleanyourtv` 仅作为界面和交互设计参考。本次实现没有复制其代码、资源或许可证文本；设计参考不表示上游参与、认可或为本项目提供支持。

## AndroidX 与 Media3

AndroidX 组件及 AndroidX Media3 1.3.1 使用 Apache License 2.0。具体依赖以 `src` 中的 Gradle 配置和解析结果为准。

分发时应保留相关组件随附的许可证及适用的 NOTICE；依赖包中的声明仍然有效。本文件不替代依赖自身的许可证、NOTICE 或完整依赖清单。

## Gradle Wrapper

项目使用 Gradle Wrapper，并以 Gradle 8.7 构建。Gradle Wrapper 采用 Apache License 2.0；应保留 Wrapper 文件中已有的版权与许可声明。Wrapper 属于构建工具，不表示其全部代码被包含在应用 APK 中。

## 发布时的核对要求

- 不删除第三方源码文件内原有的版权与许可声明。
- 检查实际发布源码和 APK 中的许可材料是否与依赖一致。
- 新增、升级或替换第三方组件后，同步复核其许可与适用的 NOTICE。
- 用户自行导入的播放列表及其中内容不随本项目分发，其权利归属不因使用本项目而改变。
