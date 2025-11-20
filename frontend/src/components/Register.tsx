import * as React from 'react';
import { useState } from 'react';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Label } from './ui/label';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './ui/card';
import { Car, UserPlus, Loader2 } from 'lucide-react';
import { toast } from 'sonner@2.0.3';
import { userApi } from '../lib/api';
import { setSession, resolveName, SessionUser } from '../lib/auth';

interface RegisterProps {
  onRegister: (user: any) => void;
  onSwitchToLogin: () => void;
}

export function Register({ onRegister, onSwitchToLogin }: RegisterProps) {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<'rider' | 'driver'>('rider');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    try {
      const payload = {
        email,
        password,
        name,
        phone,
        user_type: role === 'rider' ? 'RIDER' : 'DRIVER',
      } as const;

      const { data } = await userApi.register(payload);

      if (!data?.success) {
        toast.error(data?.message || 'Registration failed');
        return;
      }

      const returnedId = data.user_id || data.userId;
      const user: SessionUser = {
        id: returnedId || '',
        name: resolveName(name, name),
        email,
        phone,
        role: role === 'driver' ? 'driver' : 'rider',
      };
      setSession(user, data.token);
      toast.success('Account created successfully!');
      onRegister(user);
    } catch (err: any) {
      toast.error(err?.response?.data?.message || 'Registration failed. Please try again.');
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
          <p className="text-gray-600">Join the community today</p>
        </div>

        <Card className="shadow-xl">
          <CardHeader>
            <CardTitle>Create Account</CardTitle>
            <CardDescription>Register as a rider or driver</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSubmit} className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="name">Full Name</Label>
                <Input
                  id="name"
                  type="text"
                  placeholder="John Doe"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                />
              </div>
              
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
                <Label htmlFor="phone">Phone Number</Label>
                <Input
                  id="phone"
                  type="tel"
                  placeholder="+1 234 567 8900"
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
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
                  minLength={6}
                />
              </div>

              <div className="space-y-2">
                <Label>I want to register as</Label>
                <div className="grid grid-cols-2 gap-3">
                  <button
                    type="button"
                    onClick={() => setRole('rider')}
                    className={`p-4 rounded-lg border-2 transition-all ${
                      role === 'rider'
                        ? 'border-blue-500 bg-blue-50'
                        : 'border-gray-200 hover:border-gray-300'
                    }`}
                  >
                    <div className="text-center">
                      <div className="text-2xl mb-2">🚶</div>
                      <p className="text-sm">Rider</p>
                    </div>
                  </button>
                  <button
                    type="button"
                    onClick={() => setRole('driver')}
                    className={`p-4 rounded-lg border-2 transition-all ${
                      role === 'driver'
                        ? 'border-blue-500 bg-blue-50'
                        : 'border-gray-200 hover:border-gray-300'
                    }`}
                  >
                    <div className="text-center">
                      <div className="text-2xl mb-2">🚗</div>
                      <p className="text-sm">Driver</p>
                    </div>
                  </button>
                </div>
              </div>

              <Button type="submit" className="w-full" disabled={loading}>
                {loading ? (
                  <>
                    <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                    Creating Account...
                  </>
                ) : (
                  <>
                    <UserPlus className="h-4 w-4 mr-2" />
                    Create Account
                  </>
                )}
              </Button>

              <div className="text-center text-sm">
                <span className="text-gray-600">Already have an account? </span>
                <button
                  type="button"
                  onClick={onSwitchToLogin}
                  className="text-blue-600 hover:text-blue-700 hover:underline"
                >
                  Sign in here
                </button>
              </div>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
