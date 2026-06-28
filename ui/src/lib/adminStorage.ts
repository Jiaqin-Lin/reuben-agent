import { setAdminAuth as setAuth, getAdminToken, clearAdminAuth } from '../api/client';
import { decodeJwt } from '../api/admin';

export { setAuth as setAdminAuth, getAdminToken, clearAdminAuth, decodeJwt };
