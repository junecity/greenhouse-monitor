"use client";

import { useMemo, useState } from "react";
import { RefreshCw } from "lucide-react";
import { AppShell } from "./app-shell";
import { ConfirmButton, EmptyState, ErrorPanel, LoadingPanel, MetricCard, Panel, SearchField, StatusBadge, TrendChart } from "./ui";
import { useAdminMutation, useAdminQuery } from "@/hooks/use-admin-api";
import { can } from "@/lib/permissions";
import { formatDate, formatMoney, formatNumber, humanDuration } from "@/lib/format";
import type { AdminRole, AuditEvent, Customer, Dashboard, Device, Entitlement, Operations, Session } from "@/lib/types";

function QueryState({ loading, error, reload, children }: { loading: boolean; error: string | null; reload: () => void; children: React.ReactNode }) {
  if (loading) return <LoadingPanel />;
  if (error) return <ErrorPanel error={error} onRetry={reload} />;
  return <>{children}</>;
}

function Role(): AdminRole | undefined {
  return useAdminQuery<Session>("/admin/v1/session").data?.role;
}

export function DashboardPage() {
  const result = useAdminQuery<Dashboard>("/admin/v1/dashboard");
  const m = result.data?.metrics || {};
  const cards = [
    ["Total customers", m.totalCustomers, "Registered accounts", "default"],
    ["Active customers", m.activeCustomers, "Account access enabled", "positive"],
    ["Free accounts", m.freeAccounts, "Core monitoring", "default"],
    ["Premium subscribers", m.premiumSubscribers, "Verified paid access", "positive"],
    ["Complimentary Premium", m.complimentaryPremium, "Backend grants", "info"],
    ["Active servers", m.activeServers, "Reporting on time", "positive"],
    ["Offline servers", m.offlineServers, "Past heartbeat threshold", m.offlineServers ? "warning" : "default"],
    ["Connected sensors", m.connectedSensors, "Reported by online servers", "info"],
    ["Camera sessions", m.activeCameraSessions, "Currently authorized", "default"],
  ] as const;
  return <AppShell title="Operational overview" eyebrow="Private administration" actions={<button className="secondary-button" onClick={() => void result.reload()}><RefreshCw size={17} />Refresh</button>}>
    <QueryState {...result}>
      {result.data && <>
        <div className="metric-grid">{cards.map(([label, value, hint, tone]) => <MetricCard key={label} label={label} value={formatNumber(value)} hint={hint} tone={tone} />)}</div>
        <div className="content-grid three"><Panel><TrendChart label="Customer growth" points={result.data.trends.customerGrowth} /></Panel><Panel><TrendChart label="Premium subscriptions" points={result.data.trends.subscriptions} /></Panel><Panel><TrendChart label="Server reliability" points={result.data.trends.reliability} suffix="%" /></Panel></div>
        <div className="content-grid two"><Panel title="Operating cost" description="Actual billed cost is tag-filtered; the forecast is a run-rate estimate."><div className="split-metrics"><MetricCard label="Month to date" value={formatMoney(result.data.costs.actualMonthToDateUsd)} hint={result.data.costs.actualSource} /><MetricCard label="Month-end estimate" value={formatMoney(result.data.costs.estimatedMonthEndUsd)} hint={result.data.costs.estimateMethod || "Unavailable"} /></div></Panel><Panel title="System alerts" description="Current CloudWatch alarm states.">{result.data.alerts.length ? <div className="stack-list">{result.data.alerts.map((alarm) => <div className="list-row" key={alarm.name}><div><strong>{alarm.name}</strong><small>{alarm.reason || "No reason reported"}</small></div><StatusBadge value={alarm.state} /></div>)}</div> : <EmptyState title="No alarm records returned" detail="No alarm data was available from the operations service." />}</Panel></div>
        <p className="data-note">Last generated {formatDate(result.data.generatedAt)}. All values come from backend records; unavailable data is never estimated silently.</p>
      </>}
    </QueryState>
  </AppShell>;
}

type CustomerList = { customers: Customer[]; count: number };
type CustomerDetail = Customer & { devices: Device[]; greenhouses: { greenhouseId: string; name?: string; serverDeviceId?: string; enabled: boolean; createdAt: number }[]; alerts: { id: string; type: string; priority: string; message: string; createdAt: number; acknowledgedAt?: number }[]; entitlement: Entitlement };

export function CustomersPage() {
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const result = useAdminQuery<CustomerList>(`/admin/v1/customers?query=${encodeURIComponent(search)}`);
  return <AppShell title="Customers" eyebrow="Accounts and access" actions={<SearchField value={search} onChange={setSearch} placeholder="Search name, email, or account ID" />}>
    <QueryState {...result}>{result.data && (result.data.customers.length ? <div className="master-detail"><Panel className="table-panel"><CustomerTable customers={result.data.customers} selected={selected} onSelect={setSelected} /></Panel>{selected ? <CustomerDetailPanel accountId={selected} onChanged={result.reload} /> : <Panel><EmptyState title="Select a customer" detail="Choose a customer to review greenhouses, devices, entitlements, and recent alerts." /></Panel>}</div> : <EmptyState title="No customers found" detail={search ? "No customer matched this search." : "No customer accounts are indexed yet."} />)}</QueryState>
  </AppShell>;
}

function CustomerTable({ customers, selected, onSelect }: { customers: Customer[]; selected: string | null; onSelect: (id: string) => void }) {
  return <div className="table-scroll"><table><thead><tr><th>Customer</th><th>Plan</th><th>Status</th><th>Registered</th></tr></thead><tbody>{customers.map((customer) => <tr key={customer.accountId} className={selected === customer.accountId ? "selected" : ""} onClick={() => onSelect(customer.accountId)}><td><strong>{customer.displayName || "Unnamed account"}</strong><small>{customer.email || customer.accountId}</small></td><td><StatusBadge value={customer.plan} /></td><td><StatusBadge value={customer.state} /></td><td>{formatDate(customer.createdAt)}</td></tr>)}</tbody></table></div>;
}

function CustomerDetailPanel({ accountId, onChanged }: { accountId: string; onChanged: () => void }) {
  const detail = useAdminQuery<CustomerDetail>(`/admin/v1/customers/${encodeURIComponent(accountId)}`);
  const session = useAdminQuery<Session>("/admin/v1/session");
  const mutation = useAdminMutation();
  const [expiration, setExpiration] = useState("");
  const mutate = async (path: string, method: string, body?: object) => {
    await mutation.run(path, { method, body: body ? JSON.stringify(body) : undefined });
    await detail.reload(); onChanged();
  };
  return <Panel className="detail-panel"><QueryState {...detail}>{detail.data && <div className="detail-stack">
    <div className="detail-heading"><div><p className="eyebrow">Customer profile</p><h2>{detail.data.displayName || "Unnamed account"}</h2><p>{detail.data.email || detail.data.accountId}</p></div><StatusBadge value={detail.data.state} /></div>
    <dl className="definition-grid"><div><dt>Account ID</dt><dd>{detail.data.accountId}</dd></div><div><dt>Registered</dt><dd>{formatDate(detail.data.createdAt)}</dd></div><div><dt>Plan</dt><dd>{detail.data.entitlement.plan}</dd></div><div><dt>Source</dt><dd>{detail.data.entitlement.source}</dd></div></dl>
    {can(session.data?.role, "customer") && <div className="action-box"><h3>Owner controls</h3><div className="action-row"><ConfirmButton danger={detail.data.state === "ACTIVE"} disabled={mutation.pending} message={`${detail.data.state === "ACTIVE" ? "Suspend" : "Reactivate"} this customer account?`} onConfirm={() => void mutate(`/admin/v1/customers/${accountId}/state`, "POST", { state: detail.data?.state === "ACTIVE" ? "SUSPENDED" : "ACTIVE" })}>{detail.data.state === "ACTIVE" ? "Suspend account" : "Reactivate account"}</ConfirmButton><ConfirmButton disabled={mutation.pending} message="Revoke all customer sessions and require a secure password reset?" onConfirm={() => void mutate(`/admin/v1/customers/${accountId}/recovery`, "POST")}>Start account recovery</ConfirmButton></div></div>}
    {can(session.data?.role, "premium") && <div className="action-box"><h3>Complimentary Premium</h3><label className="field"><span>Optional expiration date</span><input type="date" value={expiration} onChange={(event) => setExpiration(event.target.value)} /></label><div className="action-row">{detail.data.entitlement.complimentary ? <ConfirmButton danger disabled={mutation.pending} message="Revoke complimentary Premium and return this account to Free?" onConfirm={() => void mutate(`/admin/v1/customers/${accountId}/complimentary`, "DELETE")}>Revoke complimentary access</ConfirmButton> : <ConfirmButton disabled={mutation.pending} message="Grant complimentary Premium remote video access?" onConfirm={() => void mutate(`/admin/v1/customers/${accountId}/complimentary`, "POST", { expiresAt: expiration ? Math.floor(new Date(`${expiration}T23:59:59Z`).getTime() / 1000) : null })}>Grant complimentary access</ConfirmButton>}</div></div>}
    <h3>Greenhouses</h3>{detail.data.greenhouses.length ? <div className="stack-list">{detail.data.greenhouses.map((item) => <div className="list-row" key={item.greenhouseId}><div><strong>{item.name || item.greenhouseId}</strong><small>Server {item.serverDeviceId || "not assigned"}</small></div><StatusBadge value={item.enabled ? "Active" : "Disabled"} /></div>)}</div> : <EmptyState title="No greenhouses" />}
    <h3>Devices</h3>{detail.data.devices.length ? <div className="stack-list">{detail.data.devices.map((device) => <div className="list-row" key={device.deviceId}><div><strong>{device.deviceId}</strong><small>{device.role} · {device.appVersion || "version not reported"}</small></div><StatusBadge value={device.online} /></div>)}</div> : <EmptyState title="No paired devices" />}
  </div>}</QueryState></Panel>;
}

type DeviceList = { devices: Device[]; count: number };

export function DevicesPage() {
  const [search, setSearch] = useState("");
  const result = useAdminQuery<DeviceList>(`/admin/v1/devices?query=${encodeURIComponent(search)}`);
  const role = Role();
  const mutation = useAdminMutation();
  const revoke = async (id: string) => { await mutation.run(`/admin/v1/devices/${id}/revoke`, { method: "POST" }); await result.reload(); };
  return <AppShell title="Greenhouses and devices" eyebrow="Connectivity and health" actions={<SearchField value={search} onChange={setSearch} placeholder="Search device, account, greenhouse, or provider" />}><QueryState {...result}>{result.data && (result.data.devices.length ? <Panel className="table-panel"><div className="table-scroll"><table><thead><tr><th>Device</th><th>Role</th><th>Heartbeat</th><th>Sensors</th><th>Camera</th><th>Status</th><th /></tr></thead><tbody>{result.data.devices.map((device) => <tr key={device.deviceId}><td><strong>{device.deviceId}</strong><small>{device.accountId} · {device.greenhouseId || "No greenhouse"}</small></td><td>{device.role}</td><td>{formatDate(device.lastHeartbeat)}</td><td>{device.sensorCount} {device.sensorProvider && `· ${device.sensorProvider}`}</td><td>{device.cameraStatus || "Not reported"}</td><td><StatusBadge value={device.online} />{device.connectionError && <small className="error-copy">{device.connectionError}</small>}</td><td>{can(role, "device") && device.enabled && <ConfirmButton danger disabled={mutation.pending} message="Revoke this device session? The customer must pair it again." onConfirm={() => void revoke(device.deviceId)}>Revoke</ConfirmButton>}</td></tr>)}</tbody></table></div></Panel> : <EmptyState title="No devices found" />)}</QueryState></AppShell>;
}

type SubscriptionList = { subscriptions: Entitlement[]; count: number };

export function SubscriptionsPage() {
  const result = useAdminQuery<SubscriptionList>("/admin/v1/subscriptions");
  const role = Role();
  const mutation = useAdminMutation();
  const verify = async (id: string) => { await mutation.run(`/admin/v1/subscriptions/${id}/verify`, { method: "POST" }); await result.reload(); };
  return <AppShell title="Premium subscriptions" eyebrow="Verified entitlements"><QueryState {...result}>{result.data && (result.data.subscriptions.length ? <Panel className="table-panel"><div className="table-scroll"><table><thead><tr><th>Account</th><th>Plan</th><th>Source</th><th>Verification</th><th>Renewal / expiration</th><th /></tr></thead><tbody>{result.data.subscriptions.map((item) => <tr key={item.accountId}><td><strong>{item.accountId}</strong></td><td><StatusBadge value={item.plan} /></td><td>{item.source}</td><td><StatusBadge value={item.verificationStatus} />{item.verificationError && <small className="error-copy">{item.verificationError}</small>}</td><td>{formatDate(item.renewalAt || item.expiresAt)}</td><td>{can(role, "premium") && item.source === "GOOGLE_PLAY_VERIFIED" && <ConfirmButton disabled={mutation.pending} message="Reverify this purchase with Google Play?" onConfirm={() => void verify(item.accountId)}>Reverify</ConfirmButton>}</td></tr>)}</tbody></table></div></Panel> : <EmptyState title="No subscription records" />)}</QueryState></AppShell>;
}

export function OperationsPage() {
  const result = useAdminQuery<Operations>("/admin/v1/operations");
  return <AppShell title="Service operations" eyebrow="Streaming, cost, and health" actions={<button className="secondary-button" onClick={() => void result.reload()}><RefreshCw size={17} />Refresh</button>}><QueryState {...result}>{result.data && <>
    <div className="metric-grid compact"><MetricCard label="Active sessions" value={result.data.streaming.active} /><MetricCard label="Direct connections" value={result.data.streaming.direct} tone="positive" /><MetricCard label="TURN relays" value={result.data.streaming.turnRelayed} tone="info" /><MetricCard label="Failures" value={result.data.streaming.failures} tone={result.data.streaming.failures ? "warning" : "default"} /><MetricCard label="Reconnect attempts" value={result.data.streaming.reconnections} /><MetricCard label="Session duration" value={humanDuration(result.data.streaming.durationSeconds)} /><MetricCard label="Relay usage" value={`${formatNumber(Math.round(result.data.streaming.relayBytes / 1024 / 1024))} MB`} /></div>
    <div className="content-grid two"><Panel title="AWS cost visibility" description="Actual billed cost is tag-filtered. Estimates are clearly labeled."><div className="split-metrics"><MetricCard label="Actual month to date" value={formatMoney(result.data.costs.actualMonthToDateUsd)} /><MetricCard label="Estimated month end" value={formatMoney(result.data.costs.estimatedMonthEndUsd)} /></div></Panel><Panel title="Infrastructure alarms">{result.data.alarms.length ? <div className="stack-list">{result.data.alarms.map((alarm) => <div className="list-row" key={alarm.name}><strong>{alarm.name}</strong><StatusBadge value={alarm.state} /></div>)}</div> : <EmptyState title="No alarm data returned" />}</Panel></div>
    <p className="data-note">The portal displays connection metadata only. It cannot open or view customer camera feeds.</p>
  </>}</QueryState></AppShell>;
}

export function AuditPage() {
  const result = useAdminQuery<{ events: AuditEvent[] }>("/admin/v1/audit");
  return <AppShell title="Administrative audit" eyebrow="Security and accountability"><QueryState {...result}>{result.data && (result.data.events.length ? <Panel className="table-panel"><div className="table-scroll"><table><thead><tr><th>Time</th><th>Administrator</th><th>Action</th><th>Resource</th><th>Outcome</th></tr></thead><tbody>{result.data.events.map((item) => <tr key={item.id}><td>{formatDate(item.createdAt)}</td><td><strong>{item.actorRole}</strong><small>{item.actorSubject}</small></td><td>{item.action}</td><td>{item.resourceType}<small>{item.resourceId}</small></td><td><StatusBadge value={item.outcome} /></td></tr>)}</tbody></table></div></Panel> : <EmptyState title="No audit events" detail="Administrative actions will appear here after the portal is used." />)}</QueryState></AppShell>;
}

type SupportData = { customers: Customer[]; devices: Device[]; connectionFailures: Device[]; outdatedDevices: Device[] };

export function SupportPage() {
  const [search, setSearch] = useState("");
  const result = useAdminQuery<SupportData>(`/admin/v1/support?query=${encodeURIComponent(search)}`);
  const summaries = useMemo(() => result.data ? [
    ["Matching customers", result.data.customers.length],
    ["Registered devices", result.data.devices.length],
    ["Connection failures", result.data.connectionFailures.length],
    ["Outdated app versions", result.data.outdatedDevices.length],
  ] as const : [], [result.data]);
  return <AppShell title="Customer support" eyebrow="Safe troubleshooting" actions={<SearchField value={search} onChange={setSearch} placeholder="Locate a customer or device" />}><QueryState {...result}>{result.data && <><div className="metric-grid compact">{summaries.map(([label, value]) => <MetricCard key={label} label={label} value={value} />)}</div><div className="content-grid two"><Panel title="Connection failures">{result.data.connectionFailures.length ? <div className="stack-list">{result.data.connectionFailures.map((device) => <div className="list-row" key={device.deviceId}><div><strong>{device.deviceId}</strong><small>{device.connectionError}</small></div><StatusBadge value="Failed" /></div>)}</div> : <EmptyState title="No reported connection failures" />}</Panel><Panel title="Outdated app versions">{result.data.outdatedDevices.length ? <div className="stack-list">{result.data.outdatedDevices.map((device) => <div className="list-row" key={device.deviceId}><div><strong>{device.deviceId}</strong><small>Version {device.appVersion}</small></div><StatusBadge value="Outdated" /></div>)}</div> : <EmptyState title="No outdated devices found" />}</Panel></div><p className="data-note">Support tools expose diagnostics and entitlement state only—never passwords, authentication tokens, pairing secrets, encryption keys, or video.</p></>}</QueryState></AppShell>;
}
