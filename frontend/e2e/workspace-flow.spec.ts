import {test,expect} from '@playwright/test';
const projects=['alpha','beta'].map(id=>({id,name:`${id} 프로젝트`,path:`/fixture/${id}`,rules:'',testCommand:[],createdAt:'2026-10-24'}));
test('project switching keeps conversation and knowledge in one scope',async({page})=>{
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;
  if(path.endsWith('/events/stream'))return route.abort();
  if(path.endsWith('/snapshot'))return route.fulfill({json:{projects,tasks:[],agents:[],workflowRuns:[],workflows:[],skills:[],adapters:[],eventCursor:0}});
  if(path.endsWith('/chief'))return route.fulfill({json:projects.map(p=>({id:`c-${p.id}`,projectId:p.id,state:'PLAN_READY',messages:[{role:'user',text:`${p.id} 개선 요청`,at:'2026-10-24'}],plan:{response:'계획',questions:[],steps:[{title:'구현',instruction:'개선'}]}}))});
  if(path.endsWith('/documents'))return route.fulfill({json:[{id:path.includes('/beta/')?'beta:brief':'alpha:brief',title:'프로젝트 개요',body:path.includes('/beta/')?'beta 공유 지식':'alpha 공유 지식',revision:1,author:'사용자',updatedAt:'2026-10-24'}]});
  return route.fulfill({json:[]});
 });
 await page.goto('/');
 await page.getByLabel('지금 함께 일하는 프로젝트').selectOption('beta');
 await expect(page.locator('.chief-messages')).toContainText('beta 개선 요청');
 await expect(page.locator('.chief-messages')).not.toContainText('alpha 개선 요청');
 await expect(page.locator('.room-status')).toContainText('진행할 계획');
 await page.getByLabel('총괄에게 이야기하기').fill('아직 보내지 않은 의견');
 await page.getByRole('button',{name:'공유 지식',exact:true}).click();
 await expect(page.getByLabel('공유 문서 내용')).toHaveValue('beta 공유 지식');
 await page.getByRole('button',{name:'총괄과 대화',exact:true}).click();
 await expect(page.getByLabel('총괄에게 이야기하기')).toHaveValue('아직 보내지 않은 의견');
 await page.reload();
 await expect(page.getByLabel('지금 함께 일하는 프로젝트')).toHaveValue('beta');
});
