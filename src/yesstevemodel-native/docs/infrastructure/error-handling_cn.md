# 错误模型与控制流

[返回基建总览](../infrastructure_cn.md)

主要头文件：`<err.h>`。

| 入口 | 契约 |
| --- | --- |
| `YSM_RETURN_IF_ERROR(expr)` | 失败时返回 `Status`；也接受具有 `.status()` 的结果 |
| `YSM_ASSIGN_OR_RETURN(lhs, expr)` | 将成功值移动给已声明的 `lhs` |
| `YSM_DECLARE_OR_RETURN(name, expr)` | 在当前作用域声明成功值引用；必须作为带花括号作用域内的独立语句 |
| `YSM_ASSERT(condition, status)` | 条件失败时返回指定 status |
| `YSM_RETURN_IF_NULL(...)` | 任一指针为空时返回 `InvalidArgument` |
| `DataCorruption()` | 返回 `DataLoss` |
| `YSM_ASSERT_BUF_SIZE(buf, size)` | buffer 小于下界时按数据损坏返回 |

```cpp
absl::StatusOr<BufferManaged> LoadImage(
    const fs::path& path, uint32_t raw_format) {
    BufferManaged bytes;
    YSM_RETURN_IF_ERROR(FileRead(path, bytes));
    YSM_DECLARE_OR_RETURN(format, EnumCast<codec::ImageFormat>(raw_format));
    if (format == codec::ImageFormat::kRgba) {
        return absl::InvalidArgumentError("Raw RGBA needs dimensions");
    }
    return bytes;
}
```


对 move-only 结果，先借用检查，再显式移出；下面的 `load` 返回 `StatusOr<BufferManaged>`：

```cpp
absl::StatusOr<BufferManaged> LoadNonEmpty(
    absl::FunctionRef<absl::StatusOr<BufferManaged>()> load) {
    YSM_DECLARE_OR_RETURN(bytes, load());
    YSM_ASSERT(!bytes.empty(), absl::InvalidArgumentError("Empty payload"));
    return std::move(bytes);
}
```

此例额外包含 `<absl/functional/function_ref.h>`、`<buffer_managed.h>` 和 `<utility>`。引用及其 view 不得超出实际 owner 的生命周期；移动后不再通过旧引用消费内容。
