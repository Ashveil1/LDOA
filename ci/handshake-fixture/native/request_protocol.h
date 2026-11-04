#ifndef FIXTURE_REQUEST_PROTOCOL_H
#define FIXTURE_REQUEST_PROTOCOL_H
#include <stddef.h>
#include <string.h>
/* Only a structural generation receipt. Token/pin equality is checked separately. */
static unsigned request_generation(const char*b,size_t n,unsigned last){
 if(n!=107||memcmp(b,"HSREQ1 ",7)||b[39]!=' '||b[104]!=' '||b[106]!='\n')return 0;
 for(size_t i=7;i<104;i++){if(i==39)continue;if(!((b[i]>='0'&&b[i]<='9')||(b[i]>='a'&&b[i]<='f')))return 0;}
 unsigned gen=(unsigned)(b[105]-'0');
 return gen>=1&&gen<=2&&gen==last+1?gen:0;
}
#endif
