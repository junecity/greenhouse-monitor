"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { ArrowLeft, ArrowRight, BadgeDollarSign, RefreshCw, House, Server, Smartphone, Users, Video } from "lucide-react";
import { AppShell } from "./app-shell";
import { ConfirmButton, EmptyState, ErrorPanel, LoadingPanel, MetricCard, Panel, SearchField, StatusBadge, TrendChart } from "./ui";
import { useAdminMutation, useAdminQuery } from "@/hooks/use-admin-api";
import { can } from "@/lib/permissions";
import { formatDate, formatMoney, formatNumber, humanDuration } from "@/lib/format";
import type { AddOn, AddOnKind, BillingSummary, AdminRole, AuditEvent, Customer, Dashboard, Device, Entitlement, Operations, Session } from "@/lib/types";

function QueryState({ loading, error, reload, children }: { loading: boolean; error: string | null; reload: () => void; children: React.ReactNode }) {
  if (loading) return <LoadingPanel />;
  if (error) return <ErrorPanel error={error} onRetry={reload} />;
  return <>{children}</>;
}

function Role(): AdminRole | undefined {
  return useAdminQuery<Session>("/admin/v1/session").data?.role;
}

function BillingPanel({ billing }: { billing: Dashboard["billing"] }) {
  const rows = [
    ["Extra-greenhouse subscribers", "greenhouseAccounts"],
    ["Additional greenhouse slots", "extraSlots"],
    ["Greenhouse plans ending at renewal", "endingGreenhousePlans"],
    ["Cloud recording subscribers", "recordingAccounts"],
  ] as const;
  return <Panel title="Google Play subscriptions" description="Verified access through the current billing period. Each greenhouse plan can cover multiple extra slots.">
    {billing ? <table className="billing-summary" aria-label="Verified subscription counts">
      <thead><tr><th scope="col">Subscription metric</th><th scope="col">Customer plans</th><th scope="col">License tests</th></tr></thead>
      <tbody>{rows.map(([label, key]) => <tr key={key}>
        <th scope="row">{label}</th><td>{formatNumber(billing.production[key])}</td><td>{formatNumber(billing.licenseTest[key])}</td>
      </tr>)}</tbody>
    </table> : <p className="data-note">Subscription counts are currently unavailable.</p>}
  </Panel>;
}

export function DashboardPage() {
  const result = useAdminQuery<Dashboard>("/admin/v1/dashboard");
  const m = result.data?.metrics || {};
  const cards = [
    ["Total customers", m.totalCustomers, "Registered accounts", "default"],
    ["Active customers", m.activeCustomers, "Account access enabled", "positive"],
    ["Core monitoring plans", m.freeAccounts, "Base account plan", "default"],
    ["Premium live-view plans", m.premiumSubscribers, "Verified live-view access", "positive"],
    ["Complimentary Premium", m.complimentaryPremium, "Backend grants", "info"],
    ["Active servers", m.activeServers, "Reporting on time", "positive"],
    ["Offline servers", m.offlineServers, "Past heartbeat threshold", m.offlineServers ? "warning" : "default"],
    ["Connected sensors", m.connectedSensors, "Reported by online servers", "info"],
    ["Camera sessions", m.activeCameraSessions, "Currently authorized", "default"],
  ] as const;
  const billing = result.data?.billing?.production;
  const tasks = [
    { label: "Customers", summary: formatNumber(m.totalCustomers), hint: "Find an account or help a customer", href: "/customers/", icon: Users },
    { label: "Subscriptions", summary: billing ? `${formatNumber(billing.greenhouseAccounts)} greenhouse · ${formatNumber(billing.recordingAccounts)} recording` : "Counts unavailable", hint: "See customer plans and license tests", href: "/subscriptions/", icon: BadgeDollarSign },
    { label: "Devices", summary: m.activeServers == null ? "Unavailable" : `${formatNumber(m.activeServers)} servers online`, hint: m.offlineServers ? `${formatNumber(m.offlineServers)} offline` : "Check server and sensor connections", href: "/devices/", icon: Server },
  ];
  const alarms = result.data?.alerts.filter(alarm => alarm.state === "ALARM") ?? [];
  return <AppShell title="Overview" actions={<button className="secondary-button" onClick={() => void result.reload()}><RefreshCw size={17} />Refresh</button>}>
    <QueryState {...result}>
      {result.data && <>
        <div className="admin-task-grid" aria-label="Quick links">
          {tasks.map(({ label, summary, hint, href, icon: Icon }) => <Link className="admin-task" href={href} key={href}>
            <span className="admin-task-label"><Icon size={19} />{label}<ArrowRight size={17} /></span>
            <strong>{summary}</strong><small>{hint}</small>
          </Link>)}
        </div>
        {alarms.length > 0 && <Link className="billing-notice alert-notice" href="/operations/">{alarms.length} system {alarms.length === 1 ? "alert needs" : "alerts need"} attention <ArrowRight size={16} /></Link>}
        <details className="disclosure"><summary>Account totals and trends</summary>
        <div className="metric-grid compact">{[cards[1], cards[2], cards[3], cards[4], cards[7]].map(([label, value, hint, tone]) => <MetricCard key={label} label={label} value={formatNumber(value)} hint={hint} tone={tone} />)}</div>
        <div className="content-grid three"><Panel><TrendChart label="Customer growth" points={result.data.trends.customerGrowth} /></Panel><Panel><TrendChart label="Premium subscriptions" points={result.data.trends.subscriptions} /></Panel><Panel><TrendChart label="Server reliability" points={result.data.trends.reliability} suffix="%" /></Panel></div>
        </details>
        <details className="disclosure">
          <summary>Operating cost and system health</summary>
          <div className="split-metrics">
            <MetricCard label="Month to date" value={formatMoney(result.data.costs.actualMonthToDateUsd)} hint={result.data.costs.actualSource} />
            <MetricCard label="Estimated month-end cost" value={formatMoney(result.data.costs.estimatedMonthEndUsd)} />
          </div>
          <Link className="text-link" href="/operations/">View system health <ArrowRight size={16} /></Link>
        </details>
        <p className="data-note">Last generated {formatDate(result.data.generatedAt)}. All values come from backend records; unavailable data is never estimated silently.</p>
      </>}
    </QueryState>
  </AppShell>;
}

type CustomerList = { customers: Customer[]; count: number };
type CustomerDetail = Customer & { addOns?: AddOn[]; devices: Device[]; greenhouses: { greenhouseId: string; name?: string; serverDeviceId?: string; enabled: boolean; authorizationStatus?: string; online?: boolean; lastConnection?: number; lastSensorSync?: number; cameraAvailability?: string; sensorCount?: number; batteryPercent?: number; charging?: boolean; networkType?: string; createdAt: number }[]; alerts: { id: string; type: string; priority: string; message: string; createdAt: number; acknowledgedAt?: number }[]; entitlement: Entitlement };

export function CustomersPage() {
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const result = useAdminQuery<CustomerList>(`/admin/v1/customers?query=${encodeURIComponent(search)}`);
  const activeSelection = result.data?.customers.some((customer) => customer.accountId === selected) ? selected : result.data?.customers[0]?.accountId || null;
  return <AppShell title="Customers" actions={<SearchField value={search} onChange={value => {setSearch(value); setSelected(null)}} placeholder="Search customers" />}>
    <QueryState {...result}>{result.data && (result.data.customers.length ? <div className={`master-detail customer-workspace ${selected && selected === activeSelection ? "profile-open" : ""}`}>
      <button className="back-to-directory" onClick={() => setSelected(null)}><ArrowLeft size={18} />All customers</button>
      <Panel className="table-panel"><CustomerTable customers={result.data.customers} selected={activeSelection} onSelect={setSelected} /></Panel>{activeSelection ? <CustomerDetailPanel key={activeSelection} accountId={activeSelection} onChanged={result.reload} /> : <Panel><EmptyState title="Select a customer" detail="Choose a customer to review greenhouses, devices, entitlements, and recent alerts." /></Panel>}</div> : <EmptyState title="No customers found" detail={search ? "No customer matched this search." : "No customer accounts are indexed yet."} />)}</QueryState>
  </AppShell>;
}

function CustomerTable({ customers, selected, onSelect }: { customers: Customer[]; selected: string | null; onSelect: (id: string) => void }) {
  return <div className="customer-directory" aria-label="Customers">{customers.map((customer) => <button key={customer.accountId} className={`customer-card ${selected === customer.accountId ? "selected" : ""}`} aria-pressed={selected === customer.accountId} onClick={() => onSelect(customer.accountId)}><span><strong>{customer.displayName || customer.email || "Customer account"}</strong>{customer.displayName && <small>{customer.email}</small>}<small>{customer.greenhouseCount ?? 0} {customer.greenhouseCount === 1 ? "greenhouse" : "greenhouses"} · {customer.serverCount ?? 0} {customer.serverCount === 1 ? "server" : "servers"}</small></span><StatusBadge value={customer.state} /></button>)}</div>;
}

function CustomerDetailPanel({ accountId, onChanged }: { accountId: string; onChanged: () => void }) {
  const detail = useAdminQuery<CustomerDetail>(`/admin/v1/customers/${encodeURIComponent(accountId)}`);
  const session = useAdminQuery<Session>("/admin/v1/session");
  const mutation = useAdminMutation();
  const [expiration, setExpiration] = useState("");
  const [reason, setReason] = useState("");
  const [policy, setPolicy] = useState("SUBSCRIPTION_CONTROLLED");
  const [editingPolicy, setEditingPolicy] = useState(false);
  const [actionError, setActionError] = useState("");
  const mutate = async (path: string, method: string, body?: object) => {
    setActionError("");
    try {
      await mutation.run(path, { method, body: body ? JSON.stringify(body) : undefined });
      setEditingPolicy(false);
      await detail.reload();
      onChanged();
    } catch {
      setActionError("The change could not be saved. Refresh the customer and try again.");
    }
  };
  return <Panel className="detail-panel"><QueryState {...detail}>{detail.data && <div className="detail-stack">
    <div className="detail-heading"><div><h2>{detail.data.displayName || detail.data.email || "Customer account"}</h2>{detail.data.displayName && detail.data.email !== detail.data.displayName && <p>{detail.data.email}</p>}</div><StatusBadge value={detail.data.state} /></div>
    {detail.data.setupComplete !== false && detail.data.addOns && <section className="fleet-section">
      <h3><BadgeDollarSign size={18} /> Subscriptions</h3>
      {detail.data.addOns.length ? detail.data.addOns.map(item => <div className="fleet-card" key={item.kind}>
        <div className="fleet-line"><strong>{addOnNames[item.kind]}</strong><StatusBadge value={item.state} /></div>
        <small>{addOnSummary(item)}</small>
      </div>) : <p>No greenhouse or cloud recording subscriptions.</p>}
    </section>}
    <section className="fleet-section">
      <h3><House size={18} /> Greenhouses <span>{detail.data.greenhouses.length}</span></h3>
      {detail.data.greenhouses.length === 0 && <p>No greenhouse server paired.</p>}
      {detail.data.greenhouses.map((house, index) => <article className="fleet-card" key={house.greenhouseId}>
        <div className="fleet-line"><strong>{house.name || `Greenhouse ${index + 1}`}</strong>
          <StatusBadge value={!house.serverDeviceId ? "Unpaired" : !house.enabled ? "Revoked" : house.online ? "Online" : "Offline"} /></div>
        <small>{house.sensorCount == null ? "Sensor count unavailable" : `${house.sensorCount} sensors`}{house.lastConnection ? ` · Last seen ${formatDate(house.lastConnection)}` : ""}</small>
        <details className="disclosure"><summary>Server details</summary>
          <dl className="definition-grid"><div><dt>Server ID</dt><dd>{house.serverDeviceId || "Unassigned"}</dd></div>
            <div><dt>Camera</dt><dd>{house.cameraAvailability || "Unavailable"}</dd></div>
            <div><dt>Sensor sync</dt><dd>{formatDate(house.lastSensorSync)}</dd></div>
            <div><dt>Battery</dt><dd>{house.batteryPercent == null ? "Unavailable" : `${house.batteryPercent}%`}{house.charging && " · charging"}</dd></div>
            <div><dt>Authorization</dt><dd>{house.authorizationStatus || "Unavailable"}</dd></div></dl>
          {house.enabled && house.serverDeviceId && can(session.data?.role, "device") &&
            <ConfirmButton danger disabled={mutation.pending} message={`Revoke the server for ${house.name || "this greenhouse"}? The customer will need to pair it again.`}
              onConfirm={() => void mutate(`/admin/v1/devices/${house.serverDeviceId}/revoke`, "POST")}>Revoke server</ConfirmButton>}
        </details>
      </article>)}
    </section>
    <details className="disclosure"><summary>Authorized phones ({detail.data.devices.filter(d => d.role === "VIEWER_CLIENT").length})</summary>
      {detail.data.devices.filter(d => d.role === "VIEWER_CLIENT").map((phone, i) => <div className="fleet-card" key={phone.deviceId}>
        <div className="fleet-line"><strong><Smartphone size={16} /> {phone.clientRole === "PRIMARY_CLIENT" ? "Primary phone" : `Client phone ${i + 1}`}</strong><StatusBadge value={phone.enabled ? "Authorized" : "Revoked"} /></div>
        <details className="disclosure"><summary>Phone details</summary><small>{phone.deviceId} · {phone.appVersion || "Version unavailable"}</small></details>
      </div>)}
    </details>
    {actionError && <p role="alert" className="error-copy">{actionError}</p>}
    {detail.data.setupComplete !== false && <section className="action-box camera-policy">
      <div className="fleet-line"><h3><Video size={18} /> Camera access</h3>
        {can(session.data?.role, "premium") && !editingPolicy && <button className="secondary-button" onClick={() => {
          setPolicy(detail.data?.entitlement.videoAccessMode || "SUBSCRIPTION_CONTROLLED");
          const end = detail.data?.entitlement.videoAccessOverrideExpiresAt;
          setExpiration(end ? new Date(end * 1000).toISOString().slice(0,10) : "");
          setReason(""); setEditingPolicy(true);
        }}>Change access</button>}
      </div>
      <p>{detail.data.entitlement.videoAccessMode === "COMPLIMENTARY" ? "Complimentary access" :
        detail.data.entitlement.videoAccessMode === "DISABLED" ? "Disabled" : "Follows subscription"}</p>
      {editingPolicy && can(session.data?.role, "premium") && <div className="policy-editor">
        <label className="field"><span>Streaming policy</span><select value={policy} onChange={e => setPolicy(e.target.value)}>
          <option value="SUBSCRIPTION_CONTROLLED">Follow subscription</option><option value="COMPLIMENTARY">Complimentary access</option>
          <option value="DISABLED">Disable streaming</option></select></label>
        <details className="disclosure"><summary>More options</summary>
          {policy === "COMPLIMENTARY" && <label className="field"><span>Expires (optional)</span><input type="date" value={expiration} onChange={e => setExpiration(e.target.value)} /></label>}
          <label className="field"><span>Audit reason (optional)</span><input value={reason} maxLength={240} onChange={e => setReason(e.target.value)} /></label>
        </details>
        <div className="action-row"><ConfirmButton disabled={mutation.pending} message="Apply this streaming policy to the selected customer?"
          onConfirm={() => void mutate(`/admin/v1/customers/${accountId}/video-access`, "POST", {
            mode: policy, reason, ...(policy === "COMPLIMENTARY" ? { expiresAt: expiration ? Math.floor(new Date(`${expiration}T23:59:59Z`).getTime()/1000) : null } : {})
          })}>Save access</ConfirmButton><button className="secondary-button" disabled={mutation.pending} onClick={() => setEditingPolicy(false)}>Cancel</button></div>
      </div>}
    </section>}
    <details className="disclosure"><summary>Account details</summary><dl className="definition-grid"><div><dt>Account ID</dt><dd>{detail.data.accountId}</dd></div><div><dt>Registered</dt><dd>{formatDate(detail.data.createdAt)}</dd></div><div><dt>Plan</dt><dd>{detail.data.entitlement.plan}</dd></div><div><dt>Source</dt><dd>{detail.data.entitlement.source}</dd></div></dl></details>
    <details className="disclosure"><summary>Account security & recovery</summary>
    {detail.data.setupComplete === false && <div className="action-box"><h3>Customer setup pending</h3><p>This email is registered and confirmed, but the customer has not completed app sign-in and phone setup yet.</p></div>}
    {detail.data.setupComplete !== false && can(session.data?.role, "customer") && <div className="action-box"><h3>Owner controls</h3><div className="action-row"><ConfirmButton danger={detail.data.state === "ACTIVE"} disabled={mutation.pending} message={`${detail.data.state === "ACTIVE" ? "Suspend" : "Reactivate"} this customer account?`} onConfirm={() => void mutate(`/admin/v1/customers/${accountId}/state`, "POST", { state: detail.data?.state === "ACTIVE" ? "SUSPENDED" : "ACTIVE" })}>{detail.data.state === "ACTIVE" ? "Suspend account" : "Reactivate account"}</ConfirmButton><ConfirmButton disabled={mutation.pending} message="Revoke all customer sessions and require a secure password reset?" onConfirm={() => void mutate(`/admin/v1/customers/${accountId}/recovery`, "POST")}>Start account recovery</ConfirmButton></div></div>}
    </details>
  </div>}</QueryState></Panel>;
}

function deviceLabel(device: Device): string {
  const role = device.role === "GREENHOUSE_SERVER" ? "Greenhouse server" : device.clientRole === "PRIMARY_CLIENT" ? "Primary phone" : "Client phone";
  return `${role} · ${device.deviceId.slice(-4).toUpperCase()}`;
}

type DeviceList = { devices: Device[]; count: number };

export function DevicesPage() {
  const [search, setSearch] = useState("");
  const result = useAdminQuery<DeviceList>(`/admin/v1/devices?query=${encodeURIComponent(search)}`);
  const role = Role();
  const mutation = useAdminMutation();
  const revoke = async (id: string) => { await mutation.run(`/admin/v1/devices/${id}/revoke`, { method: "POST" }); await result.reload(); };
  return <AppShell title="Greenhouses and devices" eyebrow="Connectivity and health" actions={<SearchField value={search} onChange={setSearch} placeholder="Search device, account, greenhouse, or provider" />}><QueryState {...result}>{result.data && (result.data.devices.length ? <Panel className="table-panel"><div className="table-scroll"><table><thead><tr><th>Device</th><th>Role</th><th>Heartbeat</th><th>Sensors</th><th>Camera</th><th>Status</th><th /></tr></thead><tbody>{result.data.devices.map((device) => <tr key={device.deviceId}><td><strong>{deviceLabel(device)}</strong><details><summary>Identifiers</summary><small>{device.deviceId}<br />{device.accountId}<br />{device.greenhouseId}</small></details></td><td>{device.role}</td><td>{formatDate(device.lastHeartbeat)}</td><td>{device.sensorCount} {device.sensorProvider && `· ${device.sensorProvider}`}</td><td>{device.cameraStatus || "Not reported"}</td><td><StatusBadge value={device.online} />{device.connectionError && <small className="error-copy">{device.connectionError}</small>}</td><td>{can(role, "device") && device.enabled && <ConfirmButton danger disabled={mutation.pending} message="Revoke this device session? The customer must pair it again." onConfirm={() => void revoke(device.deviceId)}>Revoke</ConfirmButton>}</td></tr>)}</tbody></table></div></Panel> : <EmptyState title="No devices found" />)}</QueryState></AppShell>;
}

type SubscriptionList = {
  subscriptions: Entitlement[];
  addOns?: AddOn[] | null;
  billing?: BillingSummary | null;
  playVerificationConfigured?: boolean;
};

const addOnNames: Record<AddOnKind, string> = {
  GREENHOUSE_PLAN: "Extra greenhouses",
  CAMERA_STORAGE: "Cloud recording",
};

function addOnSummary(item: AddOn): string {
  const pieces = [];
  if (item.extraSlots != null) {
    pieces.push(`${item.extraSlots} extra ${item.extraSlots === 1 ? "greenhouse" : "greenhouses"}`);
  }
  if (item.paidThrough) pieces.push(`Paid through ${formatDate(item.paidThrough)}`);
  if (item.testPurchase) pieces.push("License test");
  return pieces.join(" · ") || `Verified ${formatDate(item.verifiedAt)}`;
}

export function SubscriptionsPage() {
  const result = useAdminQuery<SubscriptionList>("/admin/v1/subscriptions");
  const role = Role();
  const mutation = useAdminMutation();
  const [kind, setKind] = useState<AddOnKind>("GREENHOUSE_PLAN");
  const [search, setSearch] = useState("");
  const [verifyError, setVerifyError] = useState("");
  const rows = result.data?.addOns?.filter(item => item.kind === kind &&
    `${item.customerName ?? ""} ${item.accountId}`.toLowerCase().includes(search.trim().toLowerCase())) ?? [];
  const legacy = result.data?.subscriptions.filter(item => item.plan === "PREMIUM" || item.complimentary) ?? [];

  async function verify(id: string) {
    setVerifyError("");
    try {
      await mutation.run(`/admin/v1/subscriptions/${encodeURIComponent(id)}/verify`, { method: "POST" });
      await result.reload();
    } catch {
      setVerifyError("Google Play verification failed. Try again or check the billing setup.");
    }
  }

  return <AppShell title="Subscriptions" actions={<button className="secondary-button" onClick={() => void result.reload()}><RefreshCw size={17} />Refresh</button>}>
    <QueryState {...result}>
      {result.data && <>
        {result.data.playVerificationConfigured === false && <p className="billing-notice">Google Play purchase verification needs setup.</p>}
        <div className="admin-task-grid subscription-options" aria-label="Subscription products">
          {(Object.keys(addOnNames) as AddOnKind[]).map(value => {
            const metric = value === "GREENHOUSE_PLAN" ? "greenhouseAccounts" : "recordingAccounts";
            const billing = result.data?.billing;
            const Icon = value === "GREENHOUSE_PLAN" ? House : Video;
            return <button className="admin-task" key={value} aria-pressed={kind === value} onClick={() => { setKind(value); setSearch(""); }}>
              <span className="admin-task-label"><Icon size={19} />{addOnNames[value]}</span>
              <strong>{billing ? formatNumber(billing.production[metric]) : "Unavailable"}</strong>
              <small>{billing ? `Active customer plans · ${formatNumber(billing.licenseTest[metric])} license tests` : "Subscription counts unavailable"}</small>
            </button>;
          })}
        </div>
        <Panel title={addOnNames[kind]} description={kind === "GREENHOUSE_PLAN" ? "One greenhouse is included. Plans cover additional active greenhouses." : "One plan covers cloud recording for the account."}>
          {result.data.addOns == null ? <ErrorPanel error="Subscription records are currently unavailable." onRetry={() => void result.reload()} /> :
            <>
              {result.data.addOns.some(item => item.kind === kind) && <div className="subscription-search"><SearchField value={search} onChange={setSearch} placeholder="Find a customer or account ID" /></div>}
              {rows.length ? <div className="stack-list">
                {rows.map(item => <article className="list-row subscription-row" key={item.accountId}>
                  <div><strong>{item.customerName || `Account · ${item.accountId.slice(-6)}`}</strong>
                    <small>{addOnSummary(item)}</small>
                    <details><summary>Account ID</summary><small>{item.accountId}</small></details>
                  </div><StatusBadge value={item.state} />
                </article>)}
              </div> : <EmptyState title={search ? "No matching customers" : "No subscriptions yet"} detail={search ? "Try a different name or account ID." : "Verified plans will appear here after purchase."} />}
            </>}
        </Panel>
        <details className="disclosure"><summary>Plan totals</summary><BillingPanel billing={result.data.billing} /></details>
        <details className="disclosure"><summary>Premium live view ({legacy.length})</summary>
          {verifyError && <p className="error-copy" role="alert">{verifyError}</p>}
          {legacy.length ? <div className="stack-list">{legacy.map(item => <article className="list-row subscription-row" key={item.accountId}>
            <div><strong>Account · {item.accountId.slice(-6)}</strong>
              <small>{item.complimentary ? "Complimentary access" : item.verificationStatus}</small>
              {item.verificationError && <small className="error-copy">{item.verificationError}</small>}
              <small>{formatDate(item.renewalAt || item.expiresAt)}</small>
              <details><summary>Account ID</summary><small>{item.accountId}</small></details>
            </div>
            <div className="subscription-actions">
              <StatusBadge value={item.state} />
              {can(role, "premium") && item.source === "GOOGLE_PLAY_VERIFIED" && <ConfirmButton disabled={mutation.pending || result.data?.playVerificationConfigured === false} message="Reverify this purchase with Google Play?" onConfirm={() => void verify(item.accountId)}>Reverify</ConfirmButton>}
            </div>
          </article>)}</div> : <EmptyState title="No Premium live-view plans" />}
        </details>
      </>}
    </QueryState>
  </AppShell>;
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
  return <AppShell title="Administrative audit" eyebrow="Security and accountability"><QueryState {...result}>{result.data && (result.data.events.length ? <Panel className="table-panel"><div className="table-scroll"><table><thead><tr><th>Time</th><th>Administrator</th><th>Action</th><th>Resource</th><th>Outcome</th></tr></thead><tbody>{result.data.events.map((item) => <tr key={item.id}><td>{formatDate(item.createdAt)}</td><td><strong>{item.actorRole}</strong><details><summary>Identity</summary>{item.actorSubject}</details></td><td>{item.action}</td><td>{item.resourceType}<details><summary>Identifier</summary>{item.resourceId}</details></td><td><StatusBadge value={item.outcome} /></td></tr>)}</tbody></table></div></Panel> : <EmptyState title="No audit events" detail="Administrative actions will appear here after the portal is used." />)}</QueryState></AppShell>;
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
  return <AppShell title="Customer support" eyebrow="Safe troubleshooting" actions={<SearchField value={search} onChange={setSearch} placeholder="Locate a customer or device" />}><QueryState {...result}>{result.data && <><div className="metric-grid compact">{summaries.map(([label, value]) => <MetricCard key={label} label={label} value={value} />)}</div><div className="content-grid two"><Panel title="Connection failures">{result.data.connectionFailures.length ? <div className="stack-list">{result.data.connectionFailures.map((device) => <div className="list-row" key={device.deviceId}><div><strong>{deviceLabel(device)}</strong><small>{device.connectionError}</small></div><StatusBadge value="Failed" /></div>)}</div> : <EmptyState title="No reported connection failures" />}</Panel><Panel title="Outdated app versions">{result.data.outdatedDevices.length ? <div className="stack-list">{result.data.outdatedDevices.map((device) => <div className="list-row" key={device.deviceId}><div><strong>{deviceLabel(device)}</strong><small>Version {device.appVersion}</small></div><StatusBadge value="Outdated" /></div>)}</div> : <EmptyState title="No outdated devices found" />}</Panel></div><p className="data-note">Support tools expose diagnostics and entitlement state only—never passwords, authentication tokens, pairing secrets, encryption keys, or video.</p></>}</QueryState></AppShell>;
}
