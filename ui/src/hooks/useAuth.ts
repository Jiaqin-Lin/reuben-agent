import { useCallback, useEffect, useState } from 'react';
import {
  getAdminToken,
  getAdminUser,
  clearAdminAuth,
  setAdminAuth,
} from '../api/client';
import {
  adminLogin,
  adminLogout,
  hasAdminToken,
  decodeJwt,
  type AdminLoginDto,
  type AdminUserVo,
} from '../api/admin';

export interface AuthState {
  token: string | null;
  user: AdminUserVo | null;
  isAuthenticated: boolean;
}

export function useAuth() {
  const [state, setState] = useState<AuthState>(() => {
    const token = getAdminToken();
    const user = getAdminUser() as AdminUserVo | null;
    return {
      token,
      user,
      isAuthenticated: token ? hasAdminToken() : false,
    };
  });

  const login = useCallback(async (dto: AdminLoginDto) => {
    const res = await adminLogin(dto);
    setAdminAuth(res.token, res.user);
    setState({ token: res.token, user: res.user, isAuthenticated: true });
    return res;
  }, []);

  const logout = useCallback(async () => {
    await adminLogout();
    setState({ token: null, user: null, isAuthenticated: false });
  }, []);

  // 阶段：外部清凭据后同步状态
  useEffect(() => {
    const handler = () => {
      const token = getAdminToken();
      setState((prev) =>
        prev.token === token && prev.isAuthenticated === !!token
          ? prev
          : { token, user: null, isAuthenticated: !!token && hasAdminToken() },
      );
    };
    window.addEventListener('admin-auth-cleared', handler);
    return () => window.removeEventListener('admin-auth-cleared', handler);
  }, []);

  return { ...state, login, logout, decodeJwt };
}

export { clearAdminAuth };
