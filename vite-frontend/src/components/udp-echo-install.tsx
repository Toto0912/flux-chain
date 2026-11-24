import { useCallback, useEffect, useRef, useState, type RefObject } from "react";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import toast from "react-hot-toast";
import { getUdpEchoKey } from "@/api";

export const UDP_ECHO_PORT = 19302;

export function parseUdpEchoPort(raw: string): {
  port: number | null;
  effectivePort: number;
  valid: boolean;
} {
  const trimmed = raw.trim();
  if (!trimmed) {
    return { port: null, effectivePort: UDP_ECHO_PORT, valid: true };
  }
  if (!/^\d+$/.test(trimmed)) {
    return { port: null, effectivePort: UDP_ECHO_PORT, valid: false };
  }
  const port = Number(trimmed);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    return { port: null, effectivePort: UDP_ECHO_PORT, valid: false };
  }
  return { port, effectivePort: port, valid: true };
}

export function UdpEchoInstall({
  portValue = "",
  onPortChange,
  portError,
}: {
  portValue?: string;
  onPortChange?: (value: string) => void;
  portError?: string;
} = {}) {
  const input = useRef<HTMLTextAreaElement>(null);
  const uninstallInput = useRef<HTMLTextAreaElement>(null);
  const [echoKey, setEchoKey] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const loadKey = useCallback(async () => {
    setLoading(true);
    setError(false);
    try {
      const res = await getUdpEchoKey();
      if (res.code === 0 && res.data?.key) {
        setEchoKey(res.data.key);
      } else {
        setError(true);
      }
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadKey();
  }, [loadKey]);

  const parsed = parseUdpEchoPort(portValue);
  const effectivePort = parsed.effectivePort;

  const baseUrl = new URL(`${import.meta.env.BASE_URL}udp-echo`, window.location.origin).href;
  const installer = "install-udp-echo.sh";
  const command = echoKey
    ? `curl -fL --retry 2 '${baseUrl}/${installer}' -o ${installer} && bash ./${installer} '${baseUrl}' '${echoKey}' '${effectivePort}'`
    : loading
      ? "正在获取本面板 UDP 回显鉴权密钥..."
      : "获取 UDP 回显鉴权密钥失败，请点击重试";
  const uninstallCommand =
    "systemctl disable --now fluxchain-udp-echo.service 2>/dev/null || true; rm -rf /etc/systemd/system/fluxchain-udp-echo.service /usr/local/bin/fluxchain-udp-echo /usr/local/bin/fluxchain-udp-echo.new /etc/fluxchain-udp-echo && systemctl daemon-reload && (systemctl reset-failed fluxchain-udp-echo.service 2>/dev/null || true)";

  const copyText = async (text: string, ref: RefObject<HTMLTextAreaElement | null>, successMsg: string) => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      ref.current?.focus();
      ref.current?.select();
      if (!document.execCommand("copy")) {
        toast.error("请手动复制已选中的命令");
        return;
      }
    }
    toast.success(successMsg);
  };

  return (
    <div className="min-w-0 space-y-3 rounded-lg border border-divider p-3">
      {onPortChange && (
        <Input
          type="number"
          min={1}
          max={65535}
          variant="bordered"
          label="UDP 回显端口"
          placeholder={`留空默认使用 ${UDP_ECHO_PORT} 端口`}
          description={`留空默认使用 ${UDP_ECHO_PORT} 端口，修改后下方安装命令将同步更新。`}
          value={portValue}
          onChange={(e) => onPortChange(e.target.value)}
          isInvalid={Boolean(portError) || !parsed.valid}
          errorMessage={portError || (!parsed.valid ? "UDP 回显端口必须为 1-65535 之间的整数" : undefined)}
        />
      )}

      <div className="space-y-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-sm font-medium">安装 UDP 回显服务</p>
          <div className="flex items-center gap-2">
            {error && (
              <Button size="sm" variant="flat" color="warning" onPress={() => void loadKey()}>
                重试
              </Button>
            )}
            <Button size="sm" variant="flat" color="primary" isDisabled={!echoKey || !parsed.valid}
              onPress={() => void copyText(command, input, "安装命令已复制")}>
              复制安装命令
            </Button>
          </div>
        </div>
        <p className="text-xs text-default-500">
          在每台目标服务器以 root 执行一次。命令已包含本面板的 HMAC 鉴权密钥；目标服务器需能访问面板地址并允许探测节点访问 {effectivePort}/UDP。旧版无鉴权服务请重新执行此命令升级。
        </p>
        <textarea className="w-full resize-y rounded-md bg-default-100 p-2 font-mono text-xs" ref={input}
          aria-label="UDP 回显安装命令" readOnly rows={4} value={command} />
      </div>

      <div className="space-y-2 border-t border-divider pt-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-sm font-medium">卸载 UDP 回显服务</p>
          <Button size="sm" variant="flat" color="danger"
            onPress={() => void copyText(uninstallCommand, uninstallInput, "卸载命令已复制")}>
            复制卸载命令
          </Button>
        </div>
        <p className="text-xs text-default-500">
          不再使用时在目标服务器以 root 执行，将停止服务并清理程序、systemd 配置与密钥文件。
        </p>
        <textarea className="w-full resize-y rounded-md bg-default-100 p-2 font-mono text-xs" ref={uninstallInput}
          aria-label="UDP 回显卸载命令" readOnly rows={4} value={uninstallCommand} />
      </div>
    </div>
  );
}
