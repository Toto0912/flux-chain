import { useEffect, useState } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from "@heroui/modal";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import toast from "react-hot-toast";
import { createForwardChain, deleteForwardChain, getForwardChainList, getNodeList, setForwardChainStatus, updateForwardChain } from "@/api";

type NodeItem = { id: number; name: string; serverIp: string; status: number; portSta: number; portEnd: number };
type HopForm = { nodeIds: number[] };
type ChainItem = { id: number; name: string; inNodeId: number; inPort: number; targetHost: string; targetPort: number; protocol: string; hopsJson: string; pathsJson: string; probeIntervalMs: number; probeTimeoutMs: number; switchThresholdMs: number; selectedPathIndex: number; status: number; inFlow: number; outFlow: number };
type ChainForm = { id?: number; name: string; inNodeId: number | null; inPort: string; targetHost: string; targetPort: string; protocol: string; probeIntervalSeconds: string; probeTimeoutSeconds: string; switchThresholdMs: string; hops: HopForm[] };
const emptyForm = (): ChainForm => ({ name: "", inNodeId: null, inPort: "", targetHost: "", targetPort: "", protocol: "tls", probeIntervalSeconds: "300", probeTimeoutSeconds: "3", switchThresholdMs: "1000", hops: [{ nodeIds: [] }, { nodeIds: [] }] });
const parseJson = <T,>(raw: string | undefined, fallback: T): T => { try { return raw ? JSON.parse(raw) as T : fallback; } catch { return fallback; } };
const nodeLabel = (node: NodeItem) => `${node.name} · ${node.serverIp}${node.status === 1 ? "" : "（离线）"}`;
const linkProtocols = [
  { key: "tls", label: "TLS（加密）" },
  { key: "quic", label: "QUIC（加密）" },
  { key: "tcp", label: "TCP（不额外加密）" },
  { key: "udp", label: "UDP（不额外加密）" },
];
const linkLabel = (protocol: string) => linkProtocols.find(item => item.key === protocol)?.label || protocol.toUpperCase();
const linkDescription = (protocol: string) => protocol === "udp"
  ? "UDP 业务逐跳使用原生 UDP，TCP 业务使用配套的明文 TCP 链路，两者同步选路。探测值为配套链路到目标的 TCP 参考延迟，不代表 UDP 可达性或实际 UDP 延迟。"
  : protocol === "tcp"
    ? "TCP 和 UDP 业务均通过不额外加密的 TCP 节点链路传输；UDP 业务在节点之间封装于 TCP 中。业务自身已有的加密不受影响。"
    : protocol === "quic"
      ? "节点间使用加密的 QUIC 可靠流传输，同时支持 TCP 和 UDP 业务。"
      : "节点间使用加密的 TLS 链路传输，同时支持 TCP 和 UDP 业务。";

export default function ForwardChainPage() {
  const [loading, setLoading] = useState(true);
  const [chains, setChains] = useState<ChainItem[]>([]);
  const [nodes, setNodes] = useState<NodeItem[]>([]);
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState<ChainForm>(emptyForm());

  const reload = async (showLoading = true) => {
    if (showLoading) setLoading(true);
    const [chainRes, nodeRes] = await Promise.all([getForwardChainList(), getNodeList()]);
    if (chainRes.code === 0) setChains(chainRes.data || []); else toast.error(chainRes.msg || "读取转发链失败");
    if (nodeRes.code === 0) setNodes(nodeRes.data || []); else toast.error(nodeRes.msg || "读取节点失败");
    if (showLoading) setLoading(false);
  };
  useEffect(() => {
    void reload();
    const timer = window.setInterval(() => void reload(false), 5000);
    return () => window.clearInterval(timer);
  }, []);

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
    setForm({ id: chain.id, name: chain.name, inNodeId: chain.inNodeId, inPort: String(chain.inPort), targetHost: chain.targetHost, targetPort: String(chain.targetPort), protocol: chain.protocol || "tls", probeIntervalSeconds: String(chain.probeIntervalMs / 1000), probeTimeoutSeconds: String(chain.probeTimeoutMs / 1000), switchThresholdMs: String(chain.switchThresholdMs), hops: hops.length ? hops : [{ nodeIds: [] }, { nodeIds: [] }] });
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
    if (intervalMs < 10000 || intervalMs > 3600000 || timeoutMs < 1000 || timeoutMs > 5000 || threshold < 1 || threshold > timeoutMs) { toast.error("探测间隔、超时时间或切换阈值超出允许范围"); return; }
    setSaving(true);
    const data = { id: form.id, name: form.name.trim(), inNodeId: form.inNodeId, inPort, targetHost: form.targetHost.trim(), targetPort: Number(form.targetPort), protocol: form.protocol, probeIntervalMs: intervalMs, probeTimeoutMs: timeoutMs, switchThresholdMs: threshold, hops: form.hops };
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

  return <div className="min-w-0 p-3 sm:p-4 md:p-6 space-y-5">
    <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
      <div className="min-w-0"><h1 className="text-2xl font-bold">转发链</h1><p className="text-small text-default-500 mt-1">按完整路径探测入口到目标的 TCP 延迟，并为新连接选择更快的路径。</p></div>
      <Button color="primary" className="w-full sm:w-auto sm:shrink-0" onPress={() => edit()}>创建转发链</Button>
    </div>
    {loading ? <div className="flex justify-center py-16"><Spinner label="正在读取转发链" /></div> : chains.length === 0 ? <Card><CardBody className="text-center py-12 text-default-500">还没有转发链，创建一条链路开始配置。</CardBody></Card> : <div className="grid gap-4">{chains.map(chain => {
      const paths = parseJson<Array<any>>(chain.pathsJson, []);
      const selected = paths[chain.selectedPathIndex || 0];
      const candidateCount = paths.length;
      return <Card key={chain.id} className="min-w-0">
        <CardHeader className="flex flex-col sm:flex-row sm:justify-between items-start gap-3 sm:gap-4 px-4 sm:px-5 pt-4 sm:pt-5">
          <div className="min-w-0 w-full sm:w-auto"><div className="flex gap-2 items-center flex-wrap"><h2 className="min-w-0 break-all text-lg font-semibold">{chain.name}</h2><Chip size="sm" className="shrink-0" color={chain.status === 1 ? "success" : "danger"} variant="flat">{chain.status === 1 ? "运行中" : "已暂停"}</Chip></div><p className="break-all text-small text-default-500 mt-1">入口 {nodes.find(n => n.id === chain.inNodeId)?.name || chain.inNodeId}:{chain.inPort} → {chain.targetHost}:{chain.targetPort} · {candidateCount} 条完整路径</p></div>
          <div className="grid grid-cols-3 gap-2 w-full sm:flex sm:w-auto sm:shrink-0"><Button size="sm" className="min-h-10 sm:min-h-8" variant="flat" onPress={() => edit(chain)}>编辑</Button><Button size="sm" className="min-h-10 sm:min-h-8" variant="flat" onPress={() => void toggle(chain)}>{chain.status === 1 ? "暂停" : "启用"}</Button><Button size="sm" className="min-h-10 sm:min-h-8" color="danger" variant="flat" onPress={() => void remove(chain)}>删除</Button></div>
        </CardHeader>
        <CardBody className="min-w-0 px-4 sm:px-5 pt-1 pb-5 space-y-3">
          <div className="flex flex-wrap gap-x-5 gap-y-2 text-small"><span>业务：TCP + UDP</span><span>节点链路：{linkLabel(chain.protocol || "tls")}</span><span>探测间隔：{chain.probeIntervalMs / 1000}s</span><span>超时：{chain.probeTimeoutMs / 1000}s</span><span>切换阈值：{chain.switchThresholdMs}ms</span><span>入口流量：{(((chain.inFlow || 0) + (chain.outFlow || 0)) / 1024 / 1024).toFixed(2)} MB</span></div>
          {chain.protocol === "udp" && <p className="text-small text-default-500">{linkDescription("udp")}</p>}
          <div className="break-all rounded-lg bg-default-100 p-3 text-small"><b>当前路径：</b>{selected ? selected.hops.map((h: any) => h.nodeName).join(" → ") : "第一条路径（等待探测）"}{selected?.latencyMs != null && <span className="ml-2 inline-block whitespace-nowrap text-primary">{Number(selected.latencyMs).toFixed(1)} ms</span>}</div>
          <details className="min-w-0"><summary className="cursor-pointer py-1 text-small text-default-500">查看所有路径探测结果</summary><div className="mt-2 grid gap-2">{paths.map((path: any, index: number) => <div key={path.name} className="min-w-0 flex flex-col sm:flex-row sm:justify-between gap-1 sm:gap-2 rounded border border-default-200 px-3 py-2 text-small"><span className="min-w-0 break-all">{index === chain.selectedPathIndex ? "● 当前 · " : ""}{path.hops.map((h: any) => h.nodeName).join(" → ")}</span><span className="break-all">{path.available ? `${Number(path.latencyMs).toFixed(1)} ms` : (path.error || "等待探测/不可用")}</span></div>)}</div></details>
        </CardBody>
      </Card>;
    })}</div>}

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
          <Select label="节点间链路协议" selectedKeys={[form.protocol]} onSelectionChange={keys => setForm(f => ({ ...f, protocol: String(Array.from(keys)[0] || "tls") }))}>{linkProtocols.map(p => <SelectItem key={p.key} textValue={p.label}>{p.label}</SelectItem>)}</Select>
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
        <div className="grid md:grid-cols-3 gap-3"><Input type="number" label="探测间隔（秒）" min={10} max={3600} value={form.probeIntervalSeconds} onChange={e => setForm(f => ({ ...f, probeIntervalSeconds: e.target.value }))} />
          <Input type="number" label="单次探测超时（秒）" min={1} max={5} step={0.1} value={form.probeTimeoutSeconds} onChange={e => setForm(f => ({ ...f, probeTimeoutSeconds: e.target.value }))} />
          <Input type="number" label="切换阈值（毫秒）" min={1} max={Math.round(Number(form.probeTimeoutSeconds) * 1000)} value={form.switchThresholdMs} onChange={e => setForm(f => ({ ...f, switchThresholdMs: e.target.value }))} />
        </div>
      </ModalBody><ModalFooter><Button variant="light" onPress={() => setOpen(false)}>取消</Button><Button color="primary" onPress={() => void submit()} isLoading={saving}>{form.id ? "保存修改" : "创建转发链"}</Button></ModalFooter></ModalContent>
    </Modal>
  </div>;
}
