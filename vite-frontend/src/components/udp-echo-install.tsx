import { useRef } from "react";
import { Button } from "@heroui/button";
import toast from "react-hot-toast";

export const UDP_ECHO_PORT = 19302;

export function UdpEchoInstall() {
  const input = useRef<HTMLTextAreaElement>(null);
  const baseUrl = new URL(`${import.meta.env.BASE_URL}udp-echo`, window.location.origin).href;
  const installer = "install-udp-echo.sh";
  const command = `curl -fL --retry 2 '${baseUrl}/${installer}' -o ${installer} && bash ./${installer} '${baseUrl}'`;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(command);
    } catch {
      input.current?.focus();
      input.current?.select();
      if (!document.execCommand("copy")) {
        toast.error("请手动复制已选中的命令");
        return;
      }
    }
    toast.success("安装命令已复制");
  };

  return (
    <div className="min-w-0 space-y-2 rounded-lg border border-divider p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm font-medium">安装 UDP 回显服务</p>
        <Button size="sm" variant="flat" color="primary" onPress={() => void copy()}>复制安装命令</Button>
      </div>
      <p className="text-xs text-default-500">
        在每台目标服务器以 root 执行一次。目标服务器需要能访问面板地址，并允许探测节点访问 {UDP_ECHO_PORT}/UDP。
      </p>
      <textarea className="w-full resize-y rounded-md bg-default-100 p-2 font-mono text-xs" ref={input}
        aria-label="UDP 回显安装命令" readOnly rows={3} value={command} />
    </div>
  );
}
