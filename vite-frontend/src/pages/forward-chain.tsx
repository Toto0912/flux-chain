import { useEffect, useRef, useState } from "react";
import { Accordion, AccordionItem } from "@heroui/accordion";
import { Card, CardBody, CardFooter, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from "@heroui/modal";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import { closestCenter, DndContext, KeyboardSensor, PointerSensor, useSensor, useSensors, type DragEndEvent } from "@dnd-kit/core";
import { arrayMove, rectSortingStrategy, SortableContext, sortableKeyboardCoordinates, useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import toast from "react-hot-toast";
import { createForwardChain, deleteForwardChain, getForwardChainList, getNodeList, requestForwardChainProbe, setForwardChainStatus, updateForwardChain, updateForwardChainOrder, type ForwardChainProbeStatus } from "@/api";

type NodeItem = { id: number; name: string; serverIp: string; status: number; portSta: number; portEnd: number };
type HopForm = { nodeIds: number[] };
type PathHop = { nodeName: string; port?: number };
type ChainPath = { name: string; hops: PathHop[]; available: boolean; latencyMs?: number | null; error?: string | null };
type ProbeMode = "tcp" | "udp_stun";
type ChainItem = {
  id: number;
  name: string;
  userId?: number | null;
  userName?: string | null;
  inNodeId: number;
  inPort: number;
  targetHost: string;
  targetPort: number;
  protocol: string;
  probeMode?: ProbeMode;
  hopsJson: string;
  pathsJson: string;
  probeIntervalMs: number;
  probeTimeoutMs: number;
  switchThresholdMs: number;
  selectedPathIndex: number;
  status: number;
  inFlow: number;
  outFlow: number;
  manualProbe?: ForwardChainProbeStatus | null;
};
type ChainForm = { id?: number; name: string; inNodeId: number | null; inPort: string; targetHost: string; targetPort: string; protocol: string; probeMode: ProbeMode; probeIntervalSeconds: string; probeTimeoutSeconds: string; switchThresholdMs: string; hops: HopForm[] };
type ViewMode = "direct" | "grouped";
type OwnerGroup = { name: string; entries: Map<number, ChainItem[]>; chainCount: number };
const viewModeKey = "forwardChainViewMode";
const MIN_PROBE_INTERVAL_SECONDS = 5;
const emptyForm = (): ChainForm => ({ name: "", inNodeId: null, inPort: "", targetHost: "", targetPort: "", protocol: "tls", probeMode: "tcp", probeIntervalSeconds: "300", probeTimeoutSeconds: "3", switchThresholdMs: "1000", hops: [{ nodeIds: [] }, { nodeIds: [] }] });
const stunTarget = "stun.l.google.com:19302";
const latencyLabel = (ms: number, useStun: boolean) => `${Number(ms).toFixed(1)} ms${useStun ? " RTT" : ""}`;
const parseJson = <T,>(raw: string | undefined, fallback: T): T => { try { return raw ? JSON.parse(raw) as T : fallback; } catch { return fallback; } };
const nodeLabel = (node: NodeItem) => `${node.name} · ${node.serverIp}${node.status === 1 ? "" : "（离线）"}`;
const pathLabel = (path: ChainPath) => path.hops.map(hop => `${hop.nodeName}${hop.port != null ? `:${hop.port}` : ""}`).join(" → ");
const formatFlow = (bytes: number): string => {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
  return `${value.toFixed(2)} ${units[unit]}`;
};
const entryName = (id: number, node?: NodeItem) => node?.name || `节点 ${id}`;
const formatAddress = (host: string, port: number) => {
  if (!host) return `端口 ${port}`;
  const address = host.includes(":") && !host.startsWith("[") ? `[${host}]` : host;
  return `${address}:${port}`;
};

function groupByUserAndEntry(chains: ChainItem[]) {
  const users = new Map<number | null, OwnerGroup>();
  for (const chain of chains) {
    const userId = chain.userId ?? null;
    let group = users.get(userId);
    if (!group) {
      const name = chain.userName || (userId === null ? "未记录创建人" : `用户 #${userId}`);
      group = { name, entries: new Map(), chainCount: 0 };
      users.set(userId, group);
    }
    const entry = group.entries.get(chain.inNodeId);
    if (entry) entry.push(chain);
    else group.entries.set(chain.inNodeId, [chain]);
    group.chainCount++;
  }
  return Array.from(users).sort(([, a], [, b]) => a.name.localeCompare(b.name, "zh-CN"));
}
const linkProtocols = [
  { key: "tls", label: "TLS（加密）" },
  { key: "quic", label: "QUIC（加密）" },
  { key: "tcp", label: "TCP（不额外加密）" },
  { key: "udp", label: "UDP（不额外加密）" },
];
const linkDescription = (protocol: string) => protocol === "udp"
  ? "UDP 业务逐跳使用原生 UDP，TCP 业务使用配套的明文 TCP 链路，两者同步选路。"
  : protocol === "tcp"
    ? "TCP 和 UDP 业务均通过不额外加密的 TCP 节点链路传输；UDP 业务在节点之间封装于 TCP 中。业务自身已有的加密不受影响。"
    : protocol === "quic"
      ? "节点间使用加密的 QUIC 可靠流传输，同时支持 TCP 和 UDP 业务。"
      : "节点间使用加密的 TLS 链路传输，同时支持 TCP 和 UDP 业务。";

type ChainCardProps = {
  chain: ChainItem;
  entryNode?: NodeItem;
  sortDisabled: boolean;
  requestingProbe: boolean;
  onEdit: () => void;
  onProbe: () => void;
  onToggle: () => void;
  onDelete: () => void;
};

function ChainCard({ chain, entryNode, sortDisabled, requestingProbe, onEdit, onProbe, onToggle, onDelete }: ChainCardProps) {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } =
    useSortable({ id: chain.id, disabled: sortDisabled });
  const paths = parseJson<ChainPath[]>(chain.pathsJson, []);
  const selected = paths[chain.selectedPathIndex];
  const useStun = chain.probeMode === "udp_stun";
  const running = chain.status === 1;
  const probeState = chain.manualProbe?.state;
  const probePending = probeState === "QUEUED" || probeState === "RUNNING";
  const flow = formatFlow((chain.inFlow || 0) + (chain.outFlow || 0));
  const latency = selected?.available && selected.latencyMs != null ? latencyLabel(selected.latencyMs, useStun) : "—";

  return (
    <article ref={setNodeRef} aria-label={chain.name} className={isDragging ? "relative z-10 min-w-0 opacity-80" : "min-w-0"}
      style={{ transform: CSS.Transform.toString(transform), transition }}>
      <Card className="min-w-0 border border-divider shadow-sm">
        <CardHeader className="items-start justify-between gap-2 px-3 pb-2 pt-3 sm:px-4">
          <div className="min-w-0">
            <h3 className="break-words text-sm font-semibold text-foreground">{chain.name}</h3>
            <p className="break-words text-xs text-default-500">{entryName(chain.inNodeId, entryNode)}</p>
          </div>
          <div className="flex shrink-0 items-center gap-1.5">
            <button ref={setActivatorNodeRef} type="button" {...attributes} {...listeners}
              aria-label={`拖拽排序 ${chain.name}`} title="拖拽排序" disabled={sortDisabled}
              className="inline-flex h-8 w-8 touch-none items-center justify-center rounded-md text-default-500 hover:bg-default-100 hover:text-foreground focus-visible:outline focus-visible:outline-primary active:cursor-grabbing cursor-grab disabled:cursor-default disabled:opacity-50">
              <svg aria-hidden="true" className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
                <path d="M7 2a2 2 0 1 1 .001 4.001A2 2 0 0 1 7 2zm0 6a2 2 0 1 1 .001 4.001A2 2 0 0 1 7 8zm0 6a2 2 0 1 1 .001 4.001A2 2 0 0 1 7 14zm6-8a2 2 0 1 1-.001-4.001A2 2 0 0 1 13 6zm0 2a2 2 0 1 1 .001 4.001A2 2 0 0 1 13 8zm0 6a2 2 0 1 1 .001 4.001A2 2 0 0 1 13 14z" />
              </svg>
            </button>
            <Switch size="sm" isSelected={running} aria-label={`${running ? "暂停" : "启用"} ${chain.name}`} onValueChange={onToggle} />
            <Chip size="sm" color={running ? "success" : "danger"} variant="flat">{running ? "运行中" : "已暂停"}</Chip>
          </div>
        </CardHeader>
        <CardBody className="min-w-0 gap-3 px-3 pb-3 pt-0 sm:px-4">
          <dl className="grid gap-1 rounded-md border border-default-200 bg-default-50 px-2.5 py-2 text-xs dark:bg-default-100/50">
            <div className="flex items-start gap-2"><dt className="shrink-0 text-default-500">入口</dt><dd className="min-w-0 break-all font-mono">{formatAddress(entryNode?.serverIp || "", chain.inPort)}</dd></div>
            <div className="flex items-start gap-2"><dt className="shrink-0 text-default-500">目标</dt><dd className="min-w-0 break-all font-mono">{formatAddress(chain.targetHost, chain.targetPort)}</dd></div>
          </dl>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-xs">
            <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1"><dt className="text-default-500">探测延迟</dt><dd className="font-medium tabular-nums text-primary">{latency}</dd></div>
            <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1"><dt className="text-default-500">入口流量</dt><dd className="font-medium tabular-nums">{flow}</dd></div>
            <div className="col-span-2 flex items-start gap-2"><dt className="shrink-0 text-default-500">当前路径</dt><dd className="min-w-0 break-words">{selected ? pathLabel(selected) : "等待探测"}</dd></div>
          </dl>
          <details className="min-w-0 border-t border-divider pt-2">
            <summary className="cursor-pointer break-words py-0.5 text-xs text-default-600 hover:text-foreground">查看所有路径探测结果{paths.length > 0 && <span className="ml-2 tabular-nums text-default-500">{paths.length} 条</span>}</summary>
            <div className="mt-2 grid gap-1.5 text-xs">
              <p className="break-words text-default-500">{useStun ? `UDP 往返延迟至 ${stunTarget}` : "TCP 建连耗时至业务目标"}</p>
              {paths.length === 0 ? <p className="text-default-500">等待探测</p> : paths.map((path, index) => (
                <div key={path.name} className="flex min-w-0 flex-wrap justify-between gap-x-3 gap-y-1 rounded-md border border-divider px-2 py-1.5">
                  <span className="min-w-0 break-words">{index === chain.selectedPathIndex && <span className="mr-1.5 text-primary">当前</span>}{pathLabel(path)}</span>
                  <span className="min-w-0 break-words tabular-nums text-default-600">{path.available && path.latencyMs != null ? latencyLabel(path.latencyMs, useStun) : (path.error || "等待探测/不可用")}</span>
                </div>
              ))}
            </div>
          </details>
          {chain.manualProbe && <p role="status" className={probeState === "FAILED" ? "text-xs text-danger" : "text-xs text-default-500"}>
            {chain.manualProbe.message}
          </p>}
        </CardBody>
        <CardFooter className="flex gap-1.5 px-3 pb-3 pt-0 sm:px-4">
          <Button
            size="sm"
            variant="flat"
            color="primary"
            className="flex-1 min-h-8"
            onPress={onEdit}
            startContent={
              <svg aria-hidden="true" className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                <path d="M13.586 3.586a2 2 0 112.828 2.828l-.793.793-2.828-2.828.793-.793zM11.379 5.793L3 14.172V17h2.828l8.38-8.379-2.83-2.828z" />
              </svg>
            }
          >
            编辑
          </Button>
          <Button
            size="sm"
            variant="flat"
            color="warning"
            className="flex-1 min-h-8"
            isDisabled={!running || requestingProbe || probePending}
            isLoading={requestingProbe || probeState === "RUNNING"}
            onPress={onProbe}
            startContent={
              <svg aria-hidden="true" className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
              </svg>
            }
          >
            探测
          </Button>
          <Button
            size="sm"
            variant="flat"
            color="danger"
            className="flex-1 min-h-8"
            onPress={onDelete}
            startContent={
              <svg aria-hidden="true" className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                <path fillRule="evenodd" d="M9 2a1 1 0 000 2h2a1 1 0 100-2H9z" clipRule="evenodd" />
                <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8 7a1 1 0 012 0v4a1 1 0 11-2 0V7zM12 7a1 1 0 012 0v4a1 1 0 11-2 0V7z" clipRule="evenodd" />
              </svg>
            }
          >
            删除
          </Button>
        </CardFooter>
      </Card>
    </article>
  );
}

export default function ForwardChainPage() {
  const [loading, setLoading] = useState(true);
  const [chains, setChains] = useState<ChainItem[]>([]);
  const [nodes, setNodes] = useState<NodeItem[]>([]);
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState<ChainForm>(emptyForm());
  const [viewMode, setViewMode] = useState<ViewMode>(() => window.localStorage.getItem(viewModeKey) === "grouped" ? "grouped" : "direct");
  const [savingOrder, setSavingOrder] = useState(false);
  const [requestingProbes, setRequestingProbes] = useState<Set<number>>(() => new Set());
  const orderPending = useRef(false);
  const lastLoad = useRef(0);
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const reload = async () => {
    if (orderPending.current) return;
    const request = ++lastLoad.current;
    try {
      const [chainRes, nodeRes] = await Promise.all([getForwardChainList(), getNodeList()]);
      if (request !== lastLoad.current) return;
      if (chainRes.code === 0) setChains(chainRes.data || []); else toast.error(chainRes.msg || "读取转发链失败");
      if (nodeRes.code === 0) setNodes(nodeRes.data || []); else toast.error(nodeRes.msg || "读取节点失败");
    } catch {
      if (request === lastLoad.current) toast.error("读取转发链失败");
    } finally {
      if (request === lastLoad.current) setLoading(false);
    }
  };
  useEffect(() => {
    void reload();
    const timer = window.setInterval(() => void reload(), 5000);
    return () => window.clearInterval(timer);
  }, []);

  const toggleViewMode = () => {
    const mode = viewMode === "direct" ? "grouped" : "direct";
    window.localStorage.setItem(viewModeKey, mode);
    setViewMode(mode);
  };

  const probe = async (chain: ChainItem) => {
    setRequestingProbes(ids => new Set(ids).add(chain.id));
    try {
      const response = await requestForwardChainProbe(chain.id);
      if (response.code !== 0 || !response.data) {
        toast.error(response.msg || "提交探测失败");
        return;
      }
      const manualProbe = response.data;
      lastLoad.current++;
      setChains(items => items.map(item => item.id === chain.id ? { ...item, manualProbe } : item));
      await reload();
    } catch {
      toast.error("提交探测失败");
    } finally {
      setRequestingProbes(ids => {
        const next = new Set(ids);
        next.delete(chain.id);
        return next;
      });
    }
  };

  const saveOrder = async ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id || orderPending.current) return;
    const moved = chains.find(chain => chain.id === active.id);
    if (!moved) return;
    const scope = viewMode === "direct" ? chains : chains.filter(chain =>
      chain.inNodeId === moved.inNodeId && (chain.userId ?? null) === (moved.userId ?? null));
    const destination = scope.findIndex(chain => chain.id === over.id);
    if (destination === -1) return;

    const reordered = arrayMove(scope, scope.indexOf(moved), destination);
    const scopeIds = new Set(scope.map(chain => chain.id));
    let position = 0;
    const next = chains.map(chain => scopeIds.has(chain.id) ? reordered[position++] : chain);

    orderPending.current = true;
    lastLoad.current++;
    setSavingOrder(true);
    setChains(next);
    try {
      const result = await updateForwardChainOrder(next.map(chain => chain.id));
      if (result.code !== 0) throw new Error(result.msg || "保存排序失败");
      toast.success("排序已保存");
    } catch (error) {
      setChains(chains);
      toast.error(error instanceof Error ? error.message : "保存排序失败");
    } finally {
      orderPending.current = false;
      setSavingOrder(false);
      await reload();
    }
  };

  const edit = (chain?: ChainItem) => {
    if (!chain) { setForm(emptyForm()); setOpen(true); return; }
    const seen = new Set([chain.inNodeId]);
    let removedDuplicates = false;
    const hops = parseJson<HopForm[]>(chain.hopsJson, []).map(h => ({ nodeIds: (h.nodeIds || []).filter(id => {
      if (seen.has(id)) { removedDuplicates = true; return false; }
      seen.add(id);
      return true;
    }) }));
    if (removedDuplicates) toast("已从表单中移除重复节点，请确认各跳候选服务器后保存");
    setForm({ id: chain.id, name: chain.name, inNodeId: chain.inNodeId, inPort: String(chain.inPort), targetHost: chain.targetHost, targetPort: String(chain.targetPort), protocol: chain.protocol || "tls", probeMode: chain.probeMode ?? "tcp", probeIntervalSeconds: String(chain.probeIntervalMs / 1000), probeTimeoutSeconds: String(chain.probeTimeoutMs / 1000), switchThresholdMs: String(chain.switchThresholdMs), hops: hops.length ? hops : [{ nodeIds: [] }, { nodeIds: [] }] });
    setOpen(true);
  };
  const submit = async () => {
    const intervalMs = Math.round(Number(form.probeIntervalSeconds) * 1000);
    const timeoutMs = Math.round(Number(form.probeTimeoutSeconds) * 1000);
    const threshold = Number(form.switchThresholdMs);
    const inPort = form.inPort.trim() ? Number(form.inPort) : null;
    if (inPort !== null && (!Number.isInteger(inPort) || inPort < 1 || inPort > 65535)) { toast.error("入口监听端口必须为1到65535之间的整数，或留空自动分配"); return; }
    if (!form.name.trim() || !form.inNodeId || !form.targetHost.trim() || !form.targetPort || form.hops.length < 1 || form.hops.some(h => h.nodeIds.length === 0)) { toast.error("请完整填写入口、目标和每一跳的候选节点"); return; }
    const seen = new Set([form.inNodeId]);
    for (const hop of form.hops) for (const id of hop.nodeIds) {
      if (seen.has(id)) { toast.error("入口和各跳候选服务器不能重复，请重新选择"); return; }
      seen.add(id);
    }
    if (intervalMs < MIN_PROBE_INTERVAL_SECONDS * 1000 || intervalMs > 3600000 || timeoutMs < 1000 || timeoutMs > 5000 || threshold < 1 || threshold > timeoutMs) { toast.error("探测间隔、超时时间或切换阈值超出允许范围"); return; }
    setSaving(true);
    const data = { id: form.id, name: form.name.trim(), inNodeId: form.inNodeId, inPort, targetHost: form.targetHost.trim(), targetPort: Number(form.targetPort), protocol: form.protocol, probeMode: form.probeMode, probeIntervalMs: intervalMs, probeTimeoutMs: timeoutMs, switchThresholdMs: threshold, hops: form.hops };
    const result = form.id ? await updateForwardChain(data) : await createForwardChain(data);
    setSaving(false);
    if (result.code === 0) { toast.success(form.id ? "转发链已更新" : "转发链已创建"); setOpen(false); await reload(); } else toast.error(result.msg || "保存失败");
  };
  const remove = async (chain: ChainItem) => {
    if (!window.confirm(`确定删除转发链“${chain.name}”吗？`)) return;
    const result = await deleteForwardChain(chain.id);
    if (result.code === 0) { toast.success("转发链已删除"); await reload(); } else toast.error(result.msg || "删除失败");
  };
  const toggle = async (chain: ChainItem) => {
    const result = await setForwardChainStatus(chain.id, chain.status === 1 ? 0 : 1);
    if (result.code === 0) { toast.success(chain.status === 1 ? "转发链已暂停" : "转发链已启用"); await reload(); } else toast.error(result.msg || "操作失败");
  };
  const updateEntry = (inNodeId: number | null) => setForm(f => ({
    ...f,
    inNodeId,
    hops: f.hops.map(hop => ({ nodeIds: hop.nodeIds.filter(id => id !== inNodeId) })),
  }));
  const updateHop = (index: number, nodeIds: number[]) => setForm(f => {
    const unavailable = new Set([f.inNodeId, ...f.hops.flatMap((hop, i) => i === index ? [] : hop.nodeIds)]);
    const uniqueIds = [...new Set(nodeIds)].filter(id => !unavailable.has(id));
    return { ...f, hops: f.hops.map((hop, i) => i === index ? { nodeIds: uniqueIds } : hop) };
  });
  const entryOptions = nodes.filter(n => n.status === 1 || n.id === form.inNodeId);
  const hopOptions = (index: number) => {
    const unavailable = new Set([form.inNodeId, ...form.hops.flatMap((hop, i) => i === index ? [] : hop.nodeIds)]);
    return nodes.filter(n => !unavailable.has(n.id) && (n.status === 1 || form.hops[index].nodeIds.includes(n.id)));
  };
  const nodesById = new Map(nodes.map(node => [node.id, node]));
  const viewLabel = viewMode === "grouped" ? "切换到直接显示" : "切换到分类显示";
  const renderGrid = (items: ChainItem[]) => (
    <SortableContext items={items.map(chain => chain.id)} strategy={rectSortingStrategy}>
      <div className="grid items-start gap-4 md:grid-cols-2 2xl:grid-cols-3">
        {items.map(chain => <ChainCard key={chain.id} chain={chain} entryNode={nodesById.get(chain.inNodeId)}
          sortDisabled={savingOrder || items.length < 2} requestingProbe={requestingProbes.has(chain.id)}
          onEdit={() => edit(chain)} onProbe={() => void probe(chain)} onToggle={() => void toggle(chain)}
          onDelete={() => void remove(chain)} />)}
      </div>
    </SortableContext>
  );

  return <div className="min-w-0 p-3 sm:p-4 md:p-6 space-y-5">
    <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
      <div className="min-w-0"><h1 className="text-2xl font-bold">转发链</h1><p className="text-small text-default-500 mt-1">探测每条完整路径的延迟，为新连接选择更快的路径。</p></div>
      <div className="flex shrink-0 items-center gap-2">
        <Button variant="flat" isIconOnly title={viewLabel} aria-label={viewLabel} onPress={toggleViewMode}>
          <svg aria-hidden="true" className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
            {viewMode === "grouped" ? (
              <path fillRule="evenodd" d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1v-2zM3 16a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1v-2z" clipRule="evenodd" />
            ) : (
              <path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H4a1 1 0 01-1-1v-6zM14 9a1 1 0 00-1 1v6a1 1 0 001 1h2a1 1 0 001-1v-6a1 1 0 00-1-1h-2z" />
            )}
          </svg>
        </Button>
        <Button color="primary" className="flex-1 sm:flex-none" onPress={() => edit()}>创建转发链</Button>
      </div>
    </div>
    {loading ? (
      <div className="flex justify-center py-16"><Spinner label="正在读取转发链" /></div>
    ) : chains.length === 0 ? (
      <Card><CardBody className="py-12 text-center text-default-500">还没有转发链，创建一条链路开始配置。</CardBody></Card>
    ) : (
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={event => void saveOrder(event)}>
        {viewMode === "direct" ? renderGrid(chains) : (
          <div className="space-y-4">
            {groupByUserAndEntry(chains).map(([userId, group]) => (
              <Card key={userId ?? "unknown"} className="min-w-0 border border-divider shadow-sm">
                <CardHeader className="gap-3 pb-3">
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary-100 text-primary dark:bg-primary-900/30">
                    <svg aria-hidden="true" className="h-5 w-5" fill="currentColor" viewBox="0 0 20 20">
                      <path fillRule="evenodd" d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z" clipRule="evenodd" />
                    </svg>
                  </div>
                  <div className="min-w-0">
                    <h2 className="break-words font-medium text-foreground">{group.name}</h2>
                    <p className="text-xs text-default-500">{group.entries.size} 个入口，{group.chainCount} 条转发链</p>
                  </div>
                </CardHeader>
                <CardBody className="pt-0">
                  <Accordion selectionMode="multiple" variant="splitted" className="px-0">
                    {Array.from(group.entries, ([nodeId, items]) => {
                      const name = entryName(nodeId, nodesById.get(nodeId));
                      const running = items.filter(chain => chain.status === 1).length;
                      return <AccordionItem key={nodeId} aria-label={name} className="border border-divider shadow-none"
                        title={<div className="flex min-w-0 items-center justify-between gap-3 pr-2">
                          <div className="flex min-w-0 items-center gap-2.5">
                            <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-success-100 text-success dark:bg-success-900/30">
                              <svg aria-hidden="true" className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                              </svg>
                            </span>
                            <span className="break-words text-sm font-medium">{name}</span>
                          </div>
                          <Chip size="sm" variant="flat" className="shrink-0 tabular-nums" aria-label={`${running} 条运行中，共 ${items.length} 条`}>{running}/{items.length}</Chip>
                        </div>}>
                        {renderGrid(items)}
                      </AccordionItem>;
                    })}
                  </Accordion>
                </CardBody>
              </Card>
            ))}
          </div>
        )}
      </DndContext>
    )}

    <Modal isOpen={open} onClose={() => setOpen(false)} size="3xl" placement="center" scrollBehavior="inside" classNames={{ base: "mx-2 my-2 max-h-[calc(100dvh-1rem)] sm:mx-6 sm:my-16 sm:max-h-[calc(100dvh-8rem)]", header: "px-4 sm:px-6", body: "px-4 sm:px-6", footer: "px-4 sm:px-6 pb-[max(1rem,env(safe-area-inset-bottom))]" }}>
      <ModalContent><ModalHeader>{form.id ? "编辑转发链" : "创建转发链"}</ModalHeader><ModalBody className="pb-5 space-y-4">
        <div className="grid md:grid-cols-2 gap-3"><Input label="转发链名称" value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} isRequired />
          <Select
            label="入口服务器"
            placeholder="请选择入口服务器"
            isMultiline
            classNames={{ trigger: "h-auto min-h-16 py-2", value: "whitespace-normal break-words" }}
            selectedKeys={form.inNodeId ? [String(form.inNodeId)] : []}
            onSelectionChange={keys => updateEntry(Number(Array.from(keys)[0]) || null)}
            isRequired
          >
            {entryOptions.map(n => <SelectItem key={String(n.id)} textValue={nodeLabel(n)}>{nodeLabel(n)}</SelectItem>)}
          </Select>
          <Input type="number" label="入口监听端口" placeholder="留空自动分配" description={form.inNodeId ? `允许范围：${nodes.find(n => n.id === form.inNodeId)?.portSta}–${nodes.find(n => n.id === form.inNodeId)?.portEnd}；留空自动分配，编辑时保留原入口端口` : "留空自动分配"} min={1} max={65535} value={form.inPort} onChange={e => setForm(f => ({ ...f, inPort: e.target.value }))} />
          <Select label="节点间链路协议" selectedKeys={[form.protocol]} onSelectionChange={keys => {
            const protocol = String(Array.from(keys)[0] || "tls");
            setForm(f => ({ ...f, protocol, probeMode: protocol === "udp" ? f.probeMode : "tcp" }));
          }}>{linkProtocols.map(p => <SelectItem key={p.key} textValue={p.label}>{p.label}</SelectItem>)}</Select>
          <Input label="目标地址" value={form.targetHost} onChange={e => setForm(f => ({ ...f, targetHost: e.target.value }))} isRequired />
          <Input type="number" label="目标端口" min={1} max={65535} value={form.targetPort} onChange={e => setForm(f => ({ ...f, targetPort: e.target.value }))} isRequired />
        </div>
        <p className="text-small text-default-500">{linkDescription(form.protocol)}</p>
        <div className="min-w-0 rounded-xl border border-default-200 p-3 sm:p-4 space-y-3"><div className="flex justify-between items-center gap-2"><h3 className="font-semibold">跳点与候选服务器</h3><Button size="sm" className="shrink-0" variant="flat" onPress={() => setForm(f => ({ ...f, hops: [...f.hops, { nodeIds: [] }] }))}>添加一跳</Button></div>
          {form.hops.map((hop, i) => <div key={i} className="flex flex-col sm:flex-row gap-1 sm:gap-2 sm:items-end">
            <Select
              className="w-full min-w-0 sm:flex-1"
              label={`第 ${i + 1} 跳候选服务器`}
              placeholder="请选择候选服务器（可多选）"
              selectionMode="multiple"
              isMultiline
              classNames={{ trigger: "h-auto min-h-16 py-2", value: "whitespace-normal" }}
              selectedKeys={new Set(hop.nodeIds.map(String))}
              renderValue={items => <div className="flex flex-wrap gap-1 py-1">
                {items.map(item => <Chip key={item.key} size="sm" variant="flat" classNames={{ base: "max-w-full h-auto py-1", content: "whitespace-normal break-all" }} title={item.textValue}>{item.textValue}</Chip>)}
              </div>}
              onSelectionChange={keys => updateHop(i, keys === "all" ? hopOptions(i).filter(n => n.status === 1).map(n => n.id) : Array.from(keys).map(Number))}
            >
              {hopOptions(i).map(n => <SelectItem key={String(n.id)} textValue={nodeLabel(n)}>{nodeLabel(n)}</SelectItem>)}
            </Select>
            {form.hops.length > 1 && <Button color="danger" variant="light" className="self-end sm:self-auto shrink-0" aria-label={`移除第 ${i + 1} 跳`} onPress={() => setForm(f => ({ ...f, hops: f.hops.filter((_, index) => index !== i) }))}>移除</Button>}
          </div>)}
          <p className="text-tiny text-default-500">同一条链中，入口和各跳候选服务器不能重复。系统会探测所有候选组合；路径数量较多时会限制同时探测数。</p>
        </div>
        {form.protocol === "udp" && <Select label="选路探测方式" selectedKeys={[form.probeMode]}
          description={form.probeMode === "udp_stun" ? `测量每条候选路径到 ${stunTarget} 的 UDP 往返延迟。需要更新入口及候选节点程序。` : "通过配套 TCP 链路测量到业务目标的建连耗时。"}
          onSelectionChange={keys => setForm(f => ({ ...f, probeMode: Array.from(keys)[0] === "udp_stun" ? "udp_stun" : "tcp" }))}
        >
          <SelectItem key="tcp">TCP 建连到业务目标</SelectItem>
          <SelectItem key="udp_stun">UDP STUN 到 Google</SelectItem>
        </Select>}
        <div className="grid md:grid-cols-3 gap-3"><Input type="number" label="探测间隔（秒）" min={MIN_PROBE_INTERVAL_SECONDS} max={3600} value={form.probeIntervalSeconds} onChange={e => setForm(f => ({ ...f, probeIntervalSeconds: e.target.value }))} />
          <Input type="number" label="单次探测超时（秒）" min={1} max={5} step={0.1} value={form.probeTimeoutSeconds} onChange={e => setForm(f => ({ ...f, probeTimeoutSeconds: e.target.value }))} />
          <Input type="number" label="切换阈值（毫秒）" min={1} max={Math.round(Number(form.probeTimeoutSeconds) * 1000)} value={form.switchThresholdMs} onChange={e => setForm(f => ({ ...f, switchThresholdMs: e.target.value }))} />
        </div>
      </ModalBody><ModalFooter><Button variant="light" onPress={() => setOpen(false)}>取消</Button><Button color="primary" onPress={() => void submit()} isLoading={saving}>{form.id ? "保存修改" : "创建转发链"}</Button></ModalFooter></ModalContent>
    </Modal>
  </div>;
}
