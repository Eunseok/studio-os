import {test,expect} from '@playwright/test';
test('board explains approval, closed PR and merge; does not merge without confirmation',async({page})=>{
 const p={id:'github-ui',name:'GitHub acceptance',path:'/tmp/fixture',rules:'',testCommand:[],createdAt:new Date().toISOString()};
 await page.route('**/api/snapshot',async route=>{const response=await route.fetch();const data=await response.json();data.projects=[p];await route.fulfill({json:data});});
 let merged=false;
 await page.route('**/api/projects/github-ui/github',route=>route.fulfill({json:{bindings:[{repo:'example/project',boardUrl:'https://github.com/users/example/projects/1'}],items:[{id:'one',title:'Waiting for result approval',column:'In Review',canPublish:false,message:'결과 승인 대기'},{id:'two',title:'Reviewed implementation',column:'In Review',prState:'OPEN',prUrl:'https://github.com/example/project/pull/1',head:'a'.repeat(40)},{id:'three',title:'Closed without merge',column:'In Review',prState:'CLOSED'},{id:'four',title:'Merged implementation',column:'Done',prState:'MERGED'}]}}));
 await page.route('**/api/projects/github-ui/github/merge',route=>{merged=true;return route.fulfill({json:{}});});
 await page.goto('/');await page.getByRole('button',{name:'프로젝트',exact:true}).click();
 await expect(page.getByRole('heading',{name:'GitHub 작업 보드'})).toBeVisible();await expect(page.getByText('머지되지 않고 닫힌 PR입니다.')).toBeVisible();await expect(page.getByRole('button',{name:'승인 결과로 PR 만들기'})).toHaveCount(0);
 page.once('dialog',dialog=>dialog.dismiss());await page.getByRole('button',{name:'PR 머지',exact:true}).click();expect(merged).toBeFalsy();
 page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'PR 머지',exact:true}).click();await expect.poll(()=>merged).toBeTruthy();
});
