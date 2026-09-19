'use strict';

/**
 * src/optional/xnfa.js
 * ---------------------------------------------------------------------------
 * 对开源包 `@xnfa/qq-music-api`（jsososo/QQMusicApi 的封装）做“软探测”。
 *
 * 设计意图：
 *   - 它是一个 **可选** 增强，绝不写进 package.json 的 dependencies，
 *     这样 `npm install` 在没有网络 / 没有该包时也一定成功。
 *   - 启动时 try/catch require 一次：
 *       加载成功 -> 日志说明可用，并把实例挂到 module.exports.instance
 *       加载失败 -> 打印清晰的中文警告，然后 100% 回退到本项目自带实现。
 *   - 自带实现在任何情况下都能独立工作（api.js 不依赖本文件）。
 * ---------------------------------------------------------------------------
 */

const PACKAGE_NAME = '@xnfa/qq-music-api';

let available = false;
let instance = null;
let errorMessage = '';
let probed = false;

/**
 * 探测可选包（幂等，重复调用直接返回缓存结果）。
 * @param {{ quiet?: boolean }} [opts] quiet=true 时不打印日志（测试用）
 * @returns {{ available: boolean, instance: any, packageName: string, error: string }}
 */
function probe(opts = {}) {
  if (probed) return result();
  probed = true;

  try {
    // eslint-disable-next-line global-require, import/no-dynamic-require
    const mod = require(PACKAGE_NAME);
    const exported = mod && mod.default ? mod.default : mod;
    let made = null;
    // 该包可能导出工厂函数，也可能导出类/单例，逐种尝试
    if (typeof exported === 'function') {
      try {
        made = new exported();
      } catch (err) {
        try {
          made = exported();
        } catch (err2) {
          made = null;
        }
      }
    } else if (exported && typeof exported === 'object') {
      made = exported;
    }
    available = true;
    instance = made;
    if (!opts.quiet) {
      console.log(`[optional] 检测到可选依赖 ${PACKAGE_NAME}，将作为备用实现加载（当前使用内置实现）。`);
    }
  } catch (err) {
    available = false;
    instance = null;
    errorMessage = (err && err.message) || String(err);
    if (!opts.quiet) {
      console.warn(
        `[optional] 未安装可选依赖 ${PACKAGE_NAME}（${errorMessage}）。` +
          '已自动回退到内置的完整实现，所有功能不受影响。'
      );
    }
  }

  return result();
}

function result() {
  return { available, instance, packageName: PACKAGE_NAME, error: errorMessage };
}

/** 是否已探测过 */
function isProbed() {
  return probed;
}

/** 是否可用 */
function isAvailable() {
  return available;
}

/** 取实例（不可用返回 null） */
function getInstance() {
  return available ? instance : null;
}

module.exports = {
  PACKAGE_NAME,
  probe,
  isProbed,
  isAvailable,
  getInstance,
};
