import { apiGet, apiPost, getAdminToken, clearAdminAuth } from './client';
import { ApiError } from '../types/api';
import type { ConversationSessionListVo, PageVo } from '../types/chat';

/**
 * Admin 认证 / 仪表盘 / 可观测性 API。
 *
 * 后端 auth 模块当前为 stub，登录端点未实现。
 * 这里按 super-agent 约定预置接口形态，后端就绪后可直接对接。
 */

export interface AdminLoginDto {
  username: string;
  password: string;
}

export interface AdminUserVo {
  username: string;
  nickname?: string;
  roles?: string[];
}

export interface AdminLoginVo {
  token: string;
  user: AdminUserVo;
  expiresIn?: number;
}

/** 解析 JWT payload（不验证签名，仅前端展示用）。 */
export function decodeJwt(token: string): Record<string, unknown> | null {
  try {
    const part = token.split('.')[1];
    if (!part) return null;
    const json = atob(part.replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(decodeURIComponent(escape(json)));
  } catch {
    return null;
  }
}

export async function adminLogin(dto: AdminLoginDto): Promise<AdminLoginVo> {
  // 后端 stub 期间此调用会失败，调用方负责提示
  const res = await apiPost<AdminLoginVo>('/admin/auth/login', dto);
  return res;
}

export async function adminLogout(): Promise<void> {
  try {
    await apiPost<void>('/admin/auth/logout', {});
  } finally {
    clearAdminAuth();
  }
}

export async function adminCurrentUser(): Promise<AdminUserVo> {
  return apiGet<AdminUserVo>('/admin/auth/me');
}

/** 指标卡片项。 */
export interface DashboardMetric {
  label: string;
  value: string | number;
  hint?: string;
  tone?: 'success' | 'warning' | 'danger' | 'neutral';
}

/**
 * 仪表盘指标聚合。
 * 后端暂无聚合 API，组合现有 list 接口计算文档总数 / 会话数等。
 */
export async function fetchDashboardMetrics(): Promise<DashboardMetric[]> {
  const metrics: DashboardMetric[] = [];

  try {
    const sessionPage = await apiGet<PageVo<ConversationSessionListVo>>('/chat/session/list', {
      pageNo: '1',
      pageSize: '1',
    });
    metrics.push({
      label: '会话总数',
      value: sessionPage.total ?? 0,
      hint: '历史对话会话',
    });
  } catch (e) {
    metrics.push({
      label: '会话总数',
      value: '-',
      hint: e instanceof ApiError ? e.message : '加载失败',
      tone: 'danger',
    });
  }

  return metrics;
}

/**
 * 是否携带有效 admin token（前端粗判，后端做最终鉴权）。
 *
 * 开发期可绕过守卫：后端 auth 模块为 stub 时，登录端点不存在，
 * 直接放行 admin 区域（后端无任何鉴权拦截器，端点本就裸调）。
 * 生产环境将该开关置 false 即恢复 token 校验。
 */
const BYPASS_ADMIN_AUTH = true;

export function hasAdminToken(): boolean {
  if (BYPASS_ADMIN_AUTH) return true;
  const token = getAdminToken();
  if (!token) return false;
  const payload = decodeJwt(token);
  if (!payload) return true; // 非 JWT 形态也允许尝试
  const exp = payload['exp'];
  if (typeof exp === 'number') {
    return exp * 1000 > Date.now();
  }
  return true;
}
