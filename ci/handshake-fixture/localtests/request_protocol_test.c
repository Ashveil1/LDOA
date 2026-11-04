#include "../native/request_protocol.h"
#include <assert.h>
#include <stdio.h>
int main(void){
 char b[108];memcpy(b,"HSREQ1 ",7);memset(b+7,'a',32);b[39]=' ';memset(b+40,'b',64);memcpy(b+104," 1\n",4);
 assert(request_generation(b,107,0)==1);assert(!request_generation(b,107,1));
 b[105]='2';assert(request_generation(b,107,1)==2);assert(!request_generation(b,107,0));assert(!request_generation(b,107,2));
 b[105]='3';assert(!request_generation(b,107,2));b[105]='1';b[10]='A';assert(!request_generation(b,107,0));b[10]='a';
 assert(!request_generation(b,106,0));assert(!request_generation(b,108,0));b[106]=0;assert(!request_generation(b,107,0));
 puts("REQUEST_GENERATION_10_CHECKS_PASS");return 0;
}
