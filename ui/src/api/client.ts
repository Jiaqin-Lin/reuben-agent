import { ApiResponse, ApiError } from '../types/api';

const BASE = '/api';
const ADMIN_TOKEN_KEY = 'reuben-agent-admin-token';
const ADMIN_USER_KEY = 'reuben-agent-admin-user';

/** 写入 admin 凭据（login 成功后调用）。 */
export function setAdminAuth(token: string, user: unknown) {
  window.localStorage.setItem(ADMIN_TOKEN_KEY, token);
  window.localStorage.setItem(ADMIN_USER_KEY, JSON.stringify(user));
}

/** 读取 Admin JWT，普通业务请求不携带。 */
export function getAdminToken(): string {
  return window.localStorage.getItem(ADMIN_TOKEN_KEY) || '';
}

export function getAdminUser(): unknown | null {
  const raw = window.localStorage.getItem(ADMIN_USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function clearAdminAuth() {
  window.localStorage.removeItem(ADMIN_TOKEN_KEY);
  window.localStorage.removeItem(ADMIN_USER_KEY);
}

function buildHeaders(extra?: HeadersInit): HeadersInit {
  const token = getAdminToken();
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = `Bearer ${token}`;
  if (extra) Object.assign(headers, extra as Record<string, string>);
  return headers;
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, {
    headers: buildHeaders(),
    ...options,
  });

  // 401 清理 admin 凭据
  if (res.status === 401) {
    clearAdminAuth();
  }

  if (!res.ok) {
    throw new ApiError(res.status, `HTTP ${res.status}: ${res.statusText}`);
  }

  const body: ApiResponse<T> = await res.json();

  if (body.code !== 0) {
    throw new ApiError(body.code, body.message ?? '未知错误');
  }

  return body.data;
}

export async function apiGet<T>(
  url: string,
  params?: Record<string, string>,
): Promise<T> {
  const search = params ? '?' + new URLSearchParams(params).toString() : '';
  return request<T>(`${url}${search}`);
}

export async function apiPost<T>(url: string, body?: unknown): Promise<T> {
  return request<T>(url, {
    method: 'POST',
    body: body ? JSON.stringify(body) : undefined,
  });
}

export async function apiDelete<T>(url: string): Promise<T> {
  return request<T>(url, { method: 'DELETE' });
}

export function apiUpload<T>(
  url: string,
  formData: FormData,
  onProgress?: (pct: number) => void,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${BASE}${url}`);

    const token = getAdminToken();
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`);

    xhr.upload.addEventListener('progress', (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    });

    xhr.addEventListener('load', () => {
      if (xhr.status === 401) clearAdminAuth();
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          const body: ApiResponse<T> = JSON.parse(xhr.responseText);
          if (body.code !== 0) {
            reject(new ApiError(body.code, body.message ?? '未知错误'));
          } else {
            resolve(body.data);
          }
        } catch {
          reject(new ApiError(-1, '响应解析失败'));
        }
      } else {
        reject(new ApiError(xhr.status, `HTTP ${xhr.status}`));
      }
    });

    xhr.addEventListener('error', () => reject(new ApiError(-1, '网络错误')));
    xhr.addEventListener('abort', () => reject(new ApiError(-1, '请求已取消')));

    xhr.send(formData);
  });
}

/** SSE 事件句柄。 */
export interface SseHandlers {
  onEvent?: (event: Record<string, unknown> & { type: string; content: unknown }) => void;
}

/** 打开 SSE 流，返回 controller（用于 abort）和 done promise。 */
export function openSseStream(
  url: string,
  body: unknown,
  handlers: SseHandlers,
): { controller: AbortController; done: Promise<void> } {
  const controller = new AbortController();

  const done = (async () => {
    const res = await fetch(`${BASE}${url}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(getAdminToken() ? { Authorization: `Bearer ${getAdminToken()}` } : {}),
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });

    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new ApiError(res.status, text || `HTTP ${res.status}`);
    }
    if (!res.body) {
      throw new ApiError(-1, '当前浏览器不支持流式响应');
    }

    await consumeSseStream(res.body, handlers);
  })();

  return { controller, done };
}

async function consumeSseStream(stream: ReadableStream<Uint8Array>, handlers: SseHandlers) {
  const reader = stream.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  const dispatch = (rawPayload: string) => {
    const payload = rawPayload.trim();
    if (!payload || payload === '[DONE]') return;
    try {
      handlers.onEvent?.(JSON.parse(payload));
    } catch {
      // 无法解析的事件块静默忽略，不中断流
    }
  };

  const consumeBlock = (block: string) => {
    const normalized = block.trim();
    if (!normalized) return;
    if (normalized.startsWith('data:')) {
      const payload = normalized
        .split(/\r?\n/)
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart())
        .join('\n');
      dispatch(payload);
      return;
    }
    normalized
      .split(/\r?\n/)
      .filter(Boolean)
      .forEach((line) => dispatch(line));
  };

  // 阶段：按空行切块，逐块派发
  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done });

    let boundaryIndex = buffer.search(/\r?\n\r?\n/);
    while (boundaryIndex !== -1) {
      const block = buffer.slice(0, boundaryIndex);
      const separatorMatch = buffer.slice(boundaryIndex).match(/^\r?\n\r?\n/);
      const separatorLength = separatorMatch ? separatorMatch[0].length : 2;
      buffer = buffer.slice(boundaryIndex + separatorLength);
      consumeBlock(block);
      boundaryIndex = buffer.search(/\r?\n\r?\n/);
    }

    if (done) {
      const tail = decoder.decode();
      if (tail) buffer += tail;
      if (buffer.trim()) consumeBlock(buffer);
      return;
    }
  }
}
