package com.srvcheck.core;

/** 检查项接口：只读探测，绝不修改目标系统 */
public interface Check {
    String key();

    String title();

    Category category();

    /** 该项在本分类内的相对权重 */
    int weight();

    CheckResult run();
}
