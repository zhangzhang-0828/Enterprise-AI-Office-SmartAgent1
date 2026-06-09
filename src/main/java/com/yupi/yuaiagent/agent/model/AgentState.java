package com.yupi.yuaiagent.agent.model;

/**
 * 代理执行状态的枚举类
 */
public enum AgentState {

    /**
     * 空闲状态
     */
    IDLE,

    /**
     * 运行中状态
     */
    RUNNING,

    /**
     * 已完成状态
     */
    FINISHED,

    /**
     * 错误状态
     */
    ERROR,

    /**
     * 卡死状态：循环检测发现智能体陷入重复响应，强制终止
     */
    STUCK
}