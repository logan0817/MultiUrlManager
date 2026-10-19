#!/usr/bin/env python3
"""严格检查 adb instrumentation 的终态；adb 退出码本身不能证明测试通过。"""
import re


def validate_output(output):
    output = output.replace('\r\n', '\n')
    codes = [int(code) for code in re.findall(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$', output, re.M)]
    if codes != [-1]:
        raise ValueError('instrumentation 没有唯一、成功的最终状态')
    if re.search(r'INSTRUMENTATION_(?:FAILED|ABORTED)|shortMsg=|FAILURES!!!|Process crashed', output):
        raise ValueError('instrumentation 失败、崩溃或中断')
    expected_counts = {int(n) for n in re.findall(r'^INSTRUMENTATION_STATUS: numtests=(\d+)\s*$', output, re.M)}
    if len(expected_counts) != 1 or next(iter(expected_counts)) == 0:
        raise ValueError('缺少有效测试数量或数量不一致')
    expected = next(iter(expected_counts))
    statuses = [int(code) for code in re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$', output, re.M)]
    if any(code not in (1, 2, 0, -3, -4) for code in statuses):
        raise ValueError('测试失败或出现未知状态')
    completed = sum(code in (0, -3, -4) for code in statuses)
    summaries = [int(n) for n in re.findall(r'^OK \((\d+) tests?\)\s*$', output, re.M)]
    if completed != expected or summaries != [expected] or statuses.count(0) == 0:
        raise ValueError('测试未完整执行、全部跳过或缺少成功摘要')
    return expected
