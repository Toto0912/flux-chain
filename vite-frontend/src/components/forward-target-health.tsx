import { useEffect, useState } from "react";
import { Alert } from "@heroui/alert";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from "@heroui/modal";
import { Select, SelectItem } from "@heroui/select";
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import { getForwardTargetHealth } from "@/api";
import { parseUdpEchoPort, UDP_ECHO_PORT, UdpEchoInstall } from "@/components/udp-echo-install";

export interface TargetHealthSettings {
  enabled: boolean;
  tcp: boolean;
  udpMode: "off" | "echo" | "echo_fixed" | "stun";
  udpEchoPort?: number;
  udpTargets: Record<string, string>;
  intervalSeconds: number;
  timeoutSeconds: number;
  switchThresholdMs: number;
}

export const defaultTargetHealth: TargetHealthSettings = {
  enabled: false,
  tcp: true,
  udpMode: "off",
  udpTargets: {},
  intervalSeconds: 300,
  timeoutSeconds: 3,
  switchThresholdMs: 1000,
};

export function TargetHealthFields({ settings, targets, latency, onChange }: {
  settings: TargetHealthSettings;
  targets: string[];
  latency: boolean;
  onChange: (settings: TargetHealthSettings) => void;
}) {
  const legacyEcho = settings.udpMode === "echo";
  const udpMode = legacyEcho ? "echo_fixed" : settings.udpMode;
  const showThreshold = latency && (settings.tcp || udpMode === "echo_fixed");
  const [echoPortText, setEchoPortText] = useState(() =>
    settings.udpEchoPort && settings.udpEchoPort !== UDP_ECHO_PORT ? String(settings.udpEchoPort) : ""
  );
  useEffect(() => {
    setEchoPortText(settings.udpEchoPort && settings.udpEchoPort !== UDP_ECHO_PORT ? String(settings.udpEchoPort) : "");
  }, [settings.udpEchoPort]);

  return (
    <div className="space-y-3 rounded-xl border border-divider p-3">
      <Switch size="sm" isSelected={settings.enabled} onValueChange={(enabled) => onChange({ ...settings, enabled })}>
        启用目标健康检查
      </Switch>
      {settings.enabled && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 sm:items-center">
            <Switch size="sm" isSelected={settings.tcp} onValueChange={(tcp) => onChange({ ...settings, tcp })}>
              探测 TCP 建连延迟
            </Switch>
            <Select label="UDP 探测方式" variant="bordered" selectedKeys={[udpMode]}
              onSelectionChange={(keys) => {
                const selected = Array.from(keys)[0] as TargetHealthSettings["udpMode"] | undefined;
                if (selected && selected !== udpMode) onChange({ ...settings, udpMode: selected, udpTargets: {} });
              }}>
              <SelectItem key="off">关闭</SelectItem>
              <SelectItem key="stun">UDP STUN 到 Google</SelectItem>
              <SelectItem key="echo_fixed">UDP 回显</SelectItem>
            </Select>
          </div>
          {udpMode === "stun" && (
            <p className="text-xs text-default-500">由转发出口直接探测 stun.l.google.com:19302，显示出口的 UDP 往返延迟。</p>
          )}
          {udpMode === "echo_fixed" && (
            <UdpEchoInstall
              portValue={echoPortText}
              onPortChange={(value) => {
                setEchoPortText(value);
                const parsed = parseUdpEchoPort(value);
                if (!value.trim()) {
                  onChange({ ...settings, udpEchoPort: undefined });
                } else if (parsed.valid && parsed.port !== null) {
                  onChange({ ...settings, udpEchoPort: parsed.port });
                } else {
                  onChange({ ...settings, udpEchoPort: Number(value) });
                }
              }}
            />
          )}
          {legacyEcho && (
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="text-xs text-default-500">此规则使用旧版自定义回显地址。</p>
              <Button size="sm" variant="flat" onPress={() => onChange({ ...settings, udpMode: "echo_fixed" })}>
                改用 {UDP_ECHO_PORT}/UDP
              </Button>
            </div>
          )}
          {legacyEcho && (
            <details className="space-y-2">
              <summary className="cursor-pointer text-xs text-default-600">自定义回显地址</summary>
              <p className="text-xs text-default-500">回显地址需要原样返回探测包。留空使用对应的业务目标地址。</p>
              {targets.map((target, index) => (
                <Input key={target + index} label={`UDP 探测地址 · ${target}`} variant="bordered" placeholder={target}
                  value={settings.udpTargets[target] ?? ""} onValueChange={(value) => onChange({
                    ...settings, udpTargets: { ...settings.udpTargets, [target]: value },
                  })} />
              ))}
            </details>
          )}
          <div className={`grid grid-cols-2 gap-2 ${showThreshold ? "sm:grid-cols-3" : ""}`}>
            <Input type="number" label="探测间隔（秒）" variant="bordered" min={5} max={3600}
              value={String(settings.intervalSeconds || "")}
              onValueChange={(value) => onChange({ ...settings, intervalSeconds: Number(value) })} />
            <Input type="number" label="超时（秒）" variant="bordered" min={1} max={5}
              value={String(settings.timeoutSeconds || "")}
              onValueChange={(value) => onChange({ ...settings, timeoutSeconds: Number(value) })} />
            {showThreshold && (
              <Input type="number" label="切换阈值（毫秒）" variant="bordered" min={1} max={settings.timeoutSeconds * 1000}
                value={String(settings.switchThresholdMs || "")}
                onValueChange={(value) => onChange({ ...settings, switchThresholdMs: Number(value) })} />
            )}
          </div>
          {latency && <p className="text-xs text-default-500">切换应用于新会话。未探测业务目标的协议使用主备顺序。</p>}
        </>
      )}
    </div>
  );
}

interface ProbeSample {
  probeAddress: string;
  available: boolean;
  latencyMs?: number;
  error?: string;
}

interface TargetSample extends ProbeSample {
  target: string;
}

interface ProtocolStatus {
  lastProbeAt: number;
  allUnavailable: boolean;
  selectedTarget?: string;
  targets: TargetSample[];
  egress?: ProbeSample;
}

interface TargetHealthStatus {
  nodeName: string;
  protocols: Partial<Record<"tcp" | "udp", ProtocolStatus>>;
}

function ProbeDelay({ sample, waiting = false }: { sample: ProbeSample; waiting?: boolean }) {
  return (
    <Chip size="sm" variant="flat" color={waiting ? "default" : sample.available ? "success" : "danger"}>
      {waiting ? "等待探测" : sample.available ? `${sample.latencyMs?.toFixed(1)} ms` : "未通过"}
    </Chip>
  );
}

export function TargetHealthDialog({ forwardId, name, onClose }: {
  forwardId: number;
  name: string;
  onClose: () => void;
}) {
  const [status, setStatus] = useState<TargetHealthStatus | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    let closed = false;
    let timeout: ReturnType<typeof setTimeout>;
    const refresh = async () => {
      try {
        const response = await getForwardTargetHealth(forwardId);
        if (!closed) {
          if (response.code === 0) {
            setStatus(response.data);
            setError("");
          } else {
            setError(response.msg || "暂时无法读取目标探测结果");
          }
        }
      } catch {
        if (!closed) setError("暂时无法读取目标探测结果");
      } finally {
        if (!closed) timeout = setTimeout(refresh, 3000);
      }
    };
    refresh();
    return () => {
      closed = true;
      clearTimeout(timeout);
    };
  }, [forwardId]);

  return (
    <Modal isOpen onClose={onClose} size="2xl" scrollBehavior="inside">
      <ModalContent>
        <ModalHeader className="flex flex-col gap-1">
          探测结果
          <span className="break-all text-sm font-normal text-default-500">{name}</span>
        </ModalHeader>
        <ModalBody>
          {error && <Alert color="warning" title={error} />}
          {!status && !error && <div className="p-5 text-center"><Spinner label="正在读取探测结果" /></div>}
          {status && (
            <>
              <p className="text-sm text-default-500">探测节点 {status.nodeName}</p>
              {(["tcp", "udp"] as const).map((protocol) => {
                const state = status.protocols[protocol];
                if (!state) return null;
                return (
                  <section key={protocol} className="space-y-2 rounded-xl border border-divider p-3">
                    <div className="flex flex-wrap items-center justify-between gap-1">
                      <h3 className="font-semibold">{state.egress ? "UDP STUN 到 Google" : protocol.toUpperCase()}</h3>
                      <span className="text-xs text-default-500">
                        {state.lastProbeAt ? `最近探测 ${new Date(state.lastProbeAt).toLocaleTimeString()}` : "等待首次探测"}
                      </span>
                    </div>
                    {state.selectedTarget && (
                      <p className="break-all text-sm text-primary">新会话目标 {state.selectedTarget}</p>
                    )}
                    {state.allUnavailable && <Alert color="warning" title="本轮所有目标探测失败，沿用之前的选路" />}
                    {state.egress && (
                      <div className="space-y-1 rounded-lg bg-default-100 p-2">
                        <div className="flex items-start justify-between gap-2">
                          <span className="min-w-0 break-all text-sm">出口 → <code>{state.egress.probeAddress}</code></span>
                          <ProbeDelay sample={state.egress} waiting={!state.lastProbeAt} />
                        </div>
                        {state.egress.error && <p className="break-all text-xs text-danger">{state.egress.error}</p>}
                      </div>
                    )}
                    {state.targets.map((sample) => (
                      <div key={sample.target} className="space-y-1 rounded-lg bg-default-100 p-2">
                        <div className="flex items-start justify-between gap-2">
                          <span className="min-w-0 break-all font-mono text-sm">{sample.target}</span>
                          <ProbeDelay sample={sample} />
                        </div>
                        {sample.probeAddress !== sample.target && (
                          <p className="break-all text-xs text-default-500">探测地址 {sample.probeAddress}</p>
                        )}
                        {sample.error && <p className="break-all text-xs text-danger">{sample.error}</p>}
                      </div>
                    ))}
                  </section>
                );
              })}
            </>
          )}
        </ModalBody>
        <ModalFooter><Button variant="light" onPress={onClose}>关闭</Button></ModalFooter>
      </ModalContent>
    </Modal>
  );
}
