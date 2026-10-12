#!/usr/bin/env python3
"""Check maintained Markdown links and source-backed configuration declarations offline."""
import argparse
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit

from build_support import app_config, BuildError

ROOT = Path(__file__).resolve().parent.parent


def without_fences(text):
    lines = []
    fence = None
    for line in text.splitlines():
        marker = re.match(r"\s*(`{3,}|~{3,})", line)
        if marker:
            char = marker.group(1)[0]
            if fence is None:
                fence = char
            elif fence == char:
                fence = None
            lines.append("")
        else:
            lines.append(line if fence is None else "")
    return "\n".join(lines)


def anchors(text):
    result, counts = set(), {}
    for line in without_fences(text).splitlines():
        match = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", line)
        if not match:
            continue
        heading = re.sub(r"\[([^]]+)\]\([^)]*\)", r"\1", match.group(1))
        slug = re.sub(r"[^\w\- ]", "", heading.lower()).replace(" ", "-")
        count = counts.get(slug, 0)
        counts[slug] = count + 1
        result.add(slug + ("-" + str(count) if count else ""))
    return result


def exact_case(path, root):
    relative = path.relative_to(root)
    parent = root
    for part in relative.parts:
        if part not in {child.name for child in parent.iterdir()}:
            return False
        parent /= part
    return True


def check_links(path, root):
    errors, checked = [], 0
    text = without_fences(path.read_text(encoding="utf-8"))
    for number, line in enumerate(text.splitlines(), 1):
        for match in re.finditer(r"\]\((<[^>]+>|[^)\n]+)\)", line):
            target = match.group(1).strip()
            target = target[1:-1] if target.startswith("<") else target.split(' "', 1)[0]
            url = urlsplit(target)
            if url.scheme or url.netloc:
                continue
            checked += 1
            destination = (path.parent / unquote(url.path)).resolve() if url.path else path
            label = str(path.relative_to(root)) + ":" + str(number)
            if not destination.is_relative_to(root):
                errors.append(label + " 链接越出工程：" + target)
            elif not destination.exists():
                errors.append(label + " 链接不存在：" + target)
            elif not exact_case(destination, root):
                errors.append(label + " 文件名大小写不一致：" + target)
            elif url.fragment and destination.suffix == ".md":
                if unquote(url.fragment) not in anchors(destination.read_text(encoding="utf-8")):
                    errors.append(label + " 标题锚点不存在：" + target)
    return errors, checked


def check_configuration(root):
    errors = []
    config = app_config(root)
    for name in ("README.md", "README.en.md"):
        text = (root / name).read_text(encoding="utf-8")
        declaration = re.search(r"(?:当前工程版本：|Project version:)\s*\*\*([^*]+)\*\*.*?`versionCode`\s+(\d+)", text)
        if not declaration or declaration.groups() != (config["versionName"], str(config["versionCode"])):
            errors.append(name + " 当前版本声明与 app/build.gradle.kts 不一致")
        if "`" + config["applicationId"] + "`" not in text:
            errors.append(name + " 安装包 ID 与源码不一致")
    architecture = (root / "docs/architecture.md").read_text(encoding="utf-8")
    for name in ("versionName", "versionCode", "minSdk", "compileSdk", "targetSdk"):
        if "`" + name + "=" + str(config[name]) + "`" not in architecture:
            errors.append("docs/architecture.md 的 " + name + " 与源码不一致")
    for value in (config["applicationId"], config["buildToolsVersion"]):
        if "`" + value + "`" not in architecture:
            errors.append("docs/architecture.md 缺少当前配置：" + value)
    catalog = (root / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    for name in ("agp", "kotlin", "composeBom"):
        match = re.search(r'^' + name + r'\s*=\s*"([^"]+)"', catalog, re.M)
        if not match or "`" + match.group(1) + "`" not in architecture:
            errors.append("docs/architecture.md 的工具链版本与 " + name + " 不一致")
    wrapper = (root / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    version = re.search(r"gradle-([\d.]+)-bin\.zip", wrapper)
    if not version or "`" + version.group(1) + "`" not in architecture:
        errors.append("docs/architecture.md 的 Gradle 版本与 Wrapper 不一致")
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="工程根目录（默认自动定位）")
    root = parser.parse_args(argv).root.resolve()
    paths = sorted(root.glob("*.md")) + sorted((root / "docs").rglob("*.md"))
    errors, count = [], 0
    try:
        errors.extend(check_configuration(root))
        for path in paths:
            failures, links = check_links(path, root)
            errors.extend(failures)
            count += links
    except (OSError, ValueError, BuildError) as error:
        errors.append(str(error))
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print("文档检查通过：" + str(len(paths)) + " 份 Markdown，" + str(count)
          + " 个内部链接；当前版本、平台与工具链声明一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
