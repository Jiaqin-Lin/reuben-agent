import { type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { hasAdminToken } from '../../api/admin';

/** Admin 路由守卫：未携带有效 token 则跳转登录页并携带 redirect。 */
export function ProtectedRoute({ children }: { children: ReactNode }) {
  const location = useLocation();
  if (!hasAdminToken()) {
    const redirect = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/admin/login?redirect=${redirect}`} replace />;
  }
  return <>{children}</>;
}
