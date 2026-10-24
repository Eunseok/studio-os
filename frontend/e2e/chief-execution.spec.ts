import {test,expect} from '@playwright/test';
test('room follows real states and result approval requires an explicit action',async({page})=>{
 const project={id:'p',name:'상태 확인',path:'/fixture',rules:'',testCommand:['check'],createdAt:'2026-10-24'};
 const task:any={id:'t',projectId:'p',title:'기능 개선',status:'BLOCKED',routingPending:true,requirements:[],createdAt:'2026-10-24',updatedAt:'2026-10-24',verification:'NOT_RUN',reviewStatus:'NOT_RUN',workflowInputs:[]};
 const conversation:any={id:'c',projectId:'p',state:'RUNNING',runId:'r',messages:[{role:'user',text:'기능 개선해줘',at:'2026-10-24'}]};
 const run={id:'r',project,status:'RUNNING',updatedAt:'2026-10-24',definition:{name:'기능 개선',nodes:[]},steps:{one:{attempts:['t']}}};
 let approvals=0;
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;
  if(path.endsWith('/events/stream'))return route.abort();
  if(path.endsWith('/snapshot'))return route.fulfill({json:{projects:[project],tasks:[task],agents:[],workflowRuns:[run],workflows:[],skills:[],adapters:[],eventCursor:0}});
  if(path.endsWith('/chief'))return route.fulfill({json:[conversation]});
  if(path.endsWith('/changes'))return route.fulfill({json:{diff:'diff --git a/README.md b/README.md\n-Status: waiting\n+Status: ready'}});
  if(path.endsWith('/delivery')){if(route.request().method()==='POST')approvals++;return route.fulfill({json:{status:approvals?'APPLIED':'NONE',message:'반영 완료'}});}
  return route.fulfill({json:[]});
 });
 await page.goto('/');
 // A disconnected fixture deliberately has no motion; status still identifies selection rather than failure.
 await expect(page.locator('.room-status')).not.toContainText('중단됨');
 task.routingPending=false;task.status='RUNNING';task.startedAt='2026-10-24';
 await page.reload();await expect(page.locator('.station-1')).toHaveClass(/at-work/);
 task.status='VERIFYING';task.reviewStatus='RUNNING';
 await page.reload();await expect(page.locator('.station-2')).toHaveClass(/at-work/);
 task.status='WAITING_APPROVAL';task.verification='PASSED';task.reviewStatus='PASSED';conversation.state='WAITING_RESULT';
 await page.reload();await expect(page.locator('.station-3')).toHaveClass(/at-work/);
 await expect(page.getByRole('button',{name:'결과 승인하고 반영'})).toBeEnabled();
 expect(approvals).toBe(0);
 await page.getByRole('button',{name:'결과 승인하고 반영'}).click();
 await expect(page.getByText('프로젝트에 반영했습니다',{exact:true})).toBeVisible();
 expect(approvals).toBe(1);
});
