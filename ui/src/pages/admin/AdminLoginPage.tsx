import { useState, type FormEvent } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Flask, ArrowLeft, SignIn } from '@phosphor-icons/react';
import { useAuth } from '../../hooks/useAuth';
import { useToast } from '../../components/shared/Toast';
import { ApiError } from '../../types/api';

export function AdminLoginPage() {
  const { login } = useAuth();
  const { toast } = useToast();
  const nav = useNavigate();
  const location = useLocation();

  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const redirectQuery = new URLSearchParams(location.search).get('redirect');
  const redirect = redirectQuery && redirectQuery.startsWith('/admin') ? redirectQuery : '/admin/dashboard';

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    if (!username.trim() || !password.trim()) {
      setError('请输入账号和密码');
      return;
    }
    setSubmitting(true);
    try {
      await login({ username: username.trim(), password });
      toast('登录成功', 'success');
      nav(redirect, { replace: true });
    } catch (err) {
      const msg =
        err instanceof ApiError || err instanceof Error
          ? err.message
          : '登录失败，请稍后重试';
      setError(msg);
      toast(msg, 'error');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen grid place-items-center bg-neutral-950 px-6 py-10">
      <div className="w-full max-w-4xl grid md:grid-cols-[1.15fr_0.9fr] rounded-2xl overflow-hidden border border-neutral-800 shadow-2xl">
        {/* 左侧文案 */}
        <div className="hidden md:flex flex-col justify-center gap-4 p-10 bg-gradient-to-br from-neutral-900 to-neutral-950">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-md bg-amber-500 grid place-items-center">
              <Flask weight="bold" className="w-4 h-4 text-black" />
            </div>
            <span className="text-sm font-semibold text-neutral-100 tracking-tight">reuben-agent</span>
          </div>
          <h1 className="text-2xl font-semibold text-neutral-100 leading-snug">
            进入管理后台工作台
          </h1>
          <p className="text-sm text-neutral-400 leading-relaxed max-w-md">
            这里用于管理文档接入、知识路由与对话观测。账号和密码由当前部署环境配置，登录后才能进入后台。
          </p>
          <p className="mt-2 text-[11px] text-neutral-600 font-mono">
            注：后端 auth 模块当前为 stub，登录端点尚未实现。
          </p>
        </div>

        {/* 右侧表单 */}
        <form onSubmit={submit} className="flex flex-col justify-center gap-5 p-10 bg-neutral-900/40 border-l border-neutral-800">
          <div>
            <p className="text-xs text-amber-400 font-mono uppercase tracking-wider">后台入口</p>
            <h2 className="mt-1 text-xl font-semibold text-neutral-100">管理台登录</h2>
          </div>

          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-neutral-400">账号</span>
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              type="text"
              autoComplete="username"
              placeholder="请输入后台账号"
              className="px-3 py-2.5 rounded-lg bg-neutral-950 border border-neutral-800 text-sm text-neutral-100 outline-none focus:border-amber-500/50 transition-colors"
            />
          </label>

          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-neutral-400">密码</span>
            <input
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              type="password"
              autoComplete="current-password"
              placeholder="请输入后台密码"
              className="px-3 py-2.5 rounded-lg bg-neutral-950 border border-neutral-800 text-sm text-neutral-100 outline-none focus:border-amber-500/50 transition-colors"
            />
          </label>

          {error && <p className="text-sm text-red-400">{error}</p>}

          <div className="flex gap-2.5 mt-1">
            <button
              type="button"
              onClick={() => nav('/chat')}
              className="flex-1 flex items-center justify-center gap-1.5 px-4 py-2.5 rounded-lg border border-neutral-700 text-neutral-300 text-sm font-medium hover:bg-neutral-800 transition-colors"
            >
              <ArrowLeft className="w-4 h-4" />
              返回聊天
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="flex-1 flex items-center justify-center gap-1.5 px-4 py-2.5 rounded-lg bg-amber-500 text-black text-sm font-semibold hover:bg-amber-400 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <SignIn weight="bold" className="w-4 h-4" />
              {submitting ? '登录中...' : '进入管理台'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
