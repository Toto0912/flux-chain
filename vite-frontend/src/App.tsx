import { Route, Routes, useNavigate } from "react-router-dom";
import { Suspense, lazy, useEffect, useState } from "react";

import { isLoggedIn } from "@/utils/auth";
import { siteConfig, getCachedConfig } from "@/config/site";

const IndexPage = lazy(() => import("@/pages/index"));
const ChangePasswordPage = lazy(() => import("@/pages/change-password"));
const DashboardPage = lazy(() => import("@/pages/dashboard"));
const ForwardPage = lazy(() => import("@/pages/forward"));
const ForwardChainPage = lazy(() => import("@/pages/forward-chain"));
const TunnelPage = lazy(() => import("@/pages/tunnel"));
const NodePage = lazy(() => import("@/pages/node"));
const UserPage = lazy(() => import("@/pages/user"));
const ProfilePage = lazy(() => import("@/pages/profile"));
const LimitPage = lazy(() => import("@/pages/limit"));
const ConfigPage = lazy(() => import("@/pages/config"));
const SettingsPage = lazy(() =>
  import("@/pages/settings").then((m) => ({ default: m.SettingsPage })),
);

const AdminLayout = lazy(() => import("@/layouts/admin"));
const H5Layout = lazy(() => import("@/layouts/h5"));
const H5SimpleLayout = lazy(() => import("@/layouts/h5-simple"));

const RouteLoadingFallback = () => (
  <div className="flex items-center justify-center min-h-[40vh] w-full">
    <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
  </div>
);

// 检测是否为H5模式
const useH5Mode = () => {
  // 立即检测H5模式，避免初始渲染时的闪屏
  const getInitialH5Mode = () => {
    // 检测移动设备或小屏幕
    const isMobile = window.innerWidth <= 768;
    // 检测是否为移动端浏览器
    const isMobileBrowser = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
    // 检测URL参数是否包含h5模式
    const urlParams = new URLSearchParams(window.location.search);
    const isH5Param = urlParams.get('h5') === 'true';
    
    return isMobile || isMobileBrowser || isH5Param;
  };

  const [isH5, setIsH5] = useState(getInitialH5Mode);

  useEffect(() => {
    const checkH5Mode = () => {
      // 检测移动设备或小屏幕
      const isMobile = window.innerWidth <= 768;
      // 检测是否为移动端浏览器
      const isMobileBrowser = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
      // 检测URL参数是否包含h5模式
      const urlParams = new URLSearchParams(window.location.search);
      const isH5Param = urlParams.get('h5') === 'true';
      
      setIsH5(isMobile || isMobileBrowser || isH5Param);
    };

    window.addEventListener('resize', checkH5Mode);
    
    return () => window.removeEventListener('resize', checkH5Mode);
  }, []);

  return isH5;
};

// 简化的路由保护组件 - 使用 React Router 导航避免循环
const ProtectedRoute = ({ children, useSimpleLayout = false, skipLayout = false, adminOnly = false }: { children: React.ReactNode, useSimpleLayout?: boolean, skipLayout?: boolean, adminOnly?: boolean }) => {
  const authenticated = isLoggedIn();
  const isAdmin = localStorage.getItem("role_id") === "0";
  const isH5 = useH5Mode();
  const navigate = useNavigate();
  
  useEffect(() => {
    if (!authenticated) {
      navigate('/', { replace: true });
    } else if (adminOnly && !isAdmin) {
      navigate('/dashboard', { replace: true });
    }
  }, [authenticated, adminOnly, isAdmin, navigate]);

  if (!authenticated || (adminOnly && !isAdmin)) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-white dark:bg-black">
        <div className="text-lg text-gray-700 dark:text-gray-200"></div>
      </div>
    );
  }

  // 如果跳过布局，直接返回子组件
  if (skipLayout) {
    return <>{children}</>;
  }

  // 根据模式和页面类型选择布局
  let Layout;
  if (isH5 && useSimpleLayout) {
    Layout = H5SimpleLayout;
  } else if (isH5) {
    Layout = H5Layout;
  } else {
    Layout = AdminLayout;
  }
  
  return (
    <Layout>
      <Suspense fallback={<RouteLoadingFallback />}>{children}</Suspense>
    </Layout>
  );
};


// 登录页面路由组件 - 已登录则重定向到dashboard
const LoginRoute = () => {
  const authenticated = isLoggedIn();
  const navigate = useNavigate();
  
  useEffect(() => {
    if (authenticated) {
      // 使用 React Router 导航，避免无限跳转
      navigate('/dashboard', { replace: true });
    }
  }, [authenticated, navigate]);
  
  if (authenticated) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-100 dark:bg-black">
        <div className="text-lg text-gray-700 dark:text-gray-200"></div>
      </div>
    );
  }
  
  return <IndexPage />;
};

function App() {
  // 立即设置页面标题（使用已从缓存读取的配置）
  useEffect(() => {
    document.title = siteConfig.name;
    
    // 异步检查是否有配置更新
    const checkTitleUpdate = async () => {
      try {
        const cachedAppName = await getCachedConfig('app_name');
        if (cachedAppName && cachedAppName !== document.title) {
          document.title = cachedAppName;
        }
      } catch (error) {
        console.warn('检查标题更新失败:', error);
      }
    };

    // 延迟检查，避免阻塞初始渲染
    const timer = setTimeout(checkTitleUpdate, 100);

    return () => clearTimeout(timer);
  }, []);

  return (
    <Suspense fallback={<RouteLoadingFallback />}>
      <Routes>
      <Route path="/" element={<LoginRoute />} />
      <Route 
        path="/change-password" 
        element={
          <ProtectedRoute skipLayout={true}>
            <ChangePasswordPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/dashboard" 
        element={
          <ProtectedRoute>
            <DashboardPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/forward" 
        element={
          <ProtectedRoute>
            <ForwardPage />
          </ProtectedRoute>
        } 
      />
      <Route
        path="/forward-chain"
        element={
          <ProtectedRoute adminOnly={true}>
            <ForwardChainPage />
          </ProtectedRoute>
        }
      />
      <Route 
        path="/tunnel" 
        element={
          <ProtectedRoute>
            <TunnelPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/node" 
        element={
          <ProtectedRoute>
            <NodePage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/user" 
        element={
          <ProtectedRoute useSimpleLayout={true}>
            <UserPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/profile" 
        element={
          <ProtectedRoute>
            <ProfilePage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/limit" 
        element={
          <ProtectedRoute useSimpleLayout={true}>
            <LimitPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/config" 
        element={
          <ProtectedRoute useSimpleLayout={true}>
            <ConfigPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/settings" 
        element={<SettingsPage />}
      />
    </Routes>
    </Suspense>
  );
}

export default App;
