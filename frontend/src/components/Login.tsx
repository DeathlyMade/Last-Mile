import { useState, useEffect } from 'react';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Label } from './ui/label';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './ui/card';
import { Car, LogIn, Loader2 } from 'lucide-react';
import * as React from 'react';
import { toast } from 'sonner@2.0.3';
import { userApi } from '../lib/api';
import { setSession, resolveName, SessionUser } from '../lib/auth';

interface LoginProps {
  onLogin: (user: any) => void;
  onSwitchToRegister: () => void;
}

export function Login({ onLogin, onSwitchToRegister }: LoginProps) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [infoMessage, setInfoMessage] = useState<string | null>(null);

  // Show reason message if redirected due to expiration
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const reason = params.get('reason');
    if (reason === 'expired') {
      setInfoMessage('Your session expired. Please sign in again.');
    } else if (reason === 'unauthorized') {
      setInfoMessage('Please sign in to continue.');
    }
    // Show toast instead of inline (keep inline fallback disabled)
    if (infoMessage) {
      toast.info(infoMessage);
    }
  }, [infoMessage]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    try {
      const { data } = await userApi.login({ email, password });
      if (!data?.success) {
        toast.error(data?.message || 'Invalid credentials');
        return;
      }
      // Store token early for profile fetch
      if (data.token) {
        localStorage.setItem('auth_token', data.token); // kept for interceptor compatibility
      }
      // Fetch profile for role & name details
      const userId = data.user_id || data.userId; // handle both snake_case & camelCase
      let profile: any = {};
      try {
        if (userId) {
          const profileResp = await userApi.getProfile(userId);
          profile = profileResp.data || {};
        }
      } catch {
        // swallow profile errors; we'll fallback to form/email
      }
      const profileUserId = profile.user_id || profile.userId || userId;
      // Robust role inference: accept multiple fields and case-insensitive values
      const inferRole = (p: any, loginObj: any): 'driver' | 'rider' => {
        const candidates = [
          p?.user_type,
          p?.userType,
          p?.role,
          loginObj?.user_type,
          loginObj?.userType,
          loginObj?.role,
          p?.is_driver ? 'driver' : undefined,
          p?.isDriver ? 'driver' : undefined,
        ]
          .filter(Boolean)
          .map((v: any) => String(v).toLowerCase());
        for (const v of candidates) {
          if (v.includes('driver')) return 'driver';
          if (v.includes('rider')) return 'rider';
        }
        // Default conservatively to rider if unknown
        return 'rider';
      };
      const role = inferRole(profile, data);
      const user: SessionUser = {
        id: profileUserId || '',
        name: resolveName(profile?.name, ''),
        email: profile?.email || email,
        phone: profile?.phone || '',
        role: role === 'driver' ? 'driver' : 'rider',
      };
      setSession(user, data.token);
      toast.success(`Welcome back, ${user.name}!`);
      onLogin(user);
    } catch (err: any) {
      toast.error(err?.response?.data?.message || 'Login failed. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 via-purple-50 to-pink-50 flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="inline-flex items-center gap-3 mb-4">
            <div className="bg-gradient-to-br from-blue-600 to-purple-600 p-3 rounded-xl shadow-lg">
              <Car className="h-8 w-8 text-white" />
            </div>
            <span className="text-4xl bg-gradient-to-r from-blue-600 to-purple-600 bg-clip-text text-transparent">
              LastMile
            </span>
          </div>
          <p className="text-gray-600">Your last mile, simplified</p>
        </div>

        <Card className="shadow-xl">
          <CardHeader>
            <CardTitle>Welcome Back</CardTitle>
            <CardDescription>Sign in to your account to continue</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSubmit} className="space-y-4">
              {/* Inline message removed: toast used instead */}
              <div className="space-y-2">
                <Label htmlFor="email">Email</Label>
                <Input
                  id="email"
                  type="email"
                  placeholder="you@example.com"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="password">Password</Label>
                <Input
                  id="password"
                  type="password"
                  placeholder="••••••••"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                />
              </div>

              {/* Info box removed now that real authentication is enabled */}

              <Button type="submit" className="w-full" disabled={loading}>
                {loading ? (
                  <>
                    <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                    Signing In...
                  </>
                ) : (
                  <>
                    <LogIn className="h-4 w-4 mr-2" />
                    Sign In
                  </>
                )}
              </Button>

              <div className="text-center text-sm">
                <span className="text-gray-600">Don't have an account? </span>
                <button
                  type="button"
                  onClick={onSwitchToRegister}
                  className="text-blue-600 hover:text-blue-700 hover:underline"
                >
                  Register here
                </button>
              </div>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
