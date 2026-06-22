import { ApiResponse, ApiError } from '../types/api';

const BASE = '/api';

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });

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

export function apiUpload<T>(
  url: string,
  formData: FormData,
  onProgress?: (pct: number) => void,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${BASE}${url}`);

    xhr.upload.addEventListener('progress', (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    });

    xhr.addEventListener('load', () => {
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
