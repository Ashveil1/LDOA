#define _GNU_SOURCE
#include <gio/gio.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE 1
#endif
/* Synthetic route fixture only. Never linked into PCManFM. */
typedef struct {GMainLoop*loop;int dir;const char*token;const char*pin;gboolean consumed;guint seen,published;gboolean publication_uncertain;gint64 deadline;guint last_generation;} State;
static gboolean fixedhex(const char*s,int n){if(!s||strlen(s)!=(size_t)n)return FALSE;for(int i=0;i<n;i++)if(!g_ascii_isxdigit(s[i])||g_ascii_isupper(s[i]))return FALSE;return TRUE;}
static int open_regular(State*s,const char*name,int flags){struct stat st;int fd=openat(s->dir,name,flags|O_NOFOLLOW|O_CLOEXEC|O_NONBLOCK);
 if(fd<0)return -1;
 if(fstat(fd,&st)||!S_ISREG(st.st_mode)||st.st_nlink!=1||(st.st_mode&0777)!=0600){close(fd);return -1;}return fd;}
static gboolean expire(gpointer data){State*s=data;g_print("HSREADY1 END %u %u uncertain=%u\n",s->seen,s->published,s->publication_uncertain);g_main_loop_quit(s->loop);return G_SOURCE_REMOVE;}
static void changed(GFileMonitor*m,GFile*file,GFile*other,GFileMonitorEvent ev,gpointer data){(void)m;(void)other;(void)ev;State*s=data;char*name=g_file_get_basename(file);
 if(g_strcmp0(name,"request")!=0){g_free(name);return;}g_free(name);
 if(g_get_monotonic_time()>=s->deadline)return;
 int fd=open_regular(s,"request",O_RDONLY);
 if(fd<0)return;
 char buf[256];ssize_t n=read(fd,buf,sizeof(buf));close(fd);
 /* Generation is a bounded protocol field, never inode/ctime event heuristics.
  * Only generation1 then2 is observable; generation2 cannot publish again. */
 unsigned generation=n<0?0:request_generation(buf,(size_t)n,s->last_generation);
 if(!generation)return;
 s->last_generation=generation;s->seen++;
 if(s->consumed)return;
 s->consumed=TRUE;
 char*expected=g_strdup_printf("HSREQ1 %s %s 1\n",s->token,s->pin);gboolean valid=n==(ssize_t)strlen(expected)&&memcmp(buf,expected,n)==0;g_free(expected);
 if(!valid)return;
 char*out=g_strdup_printf("HSSNAP1 %s %s 1 1\n1 0 1\n",s->token,s->pin);size_t len=strlen(out);
 if(len>524288){g_free(out);return;}
 fd=openat(s->dir,"snapshot.tmp",O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
 if(fd<0){g_free(out);return;}
 size_t at=0;while(at<len){ssize_t k=write(fd,out+at,len-at);
 if(k<0&&errno==EINTR)continue;
 if(k<=0)break;
 at+=k;}g_free(out);int rc=fsync(fd);close(fd);
 if(rc||at!=len)return;
 if(syscall(SYS_renameat2,s->dir,"snapshot.tmp",s->dir,"snapshot",RENAME_NOREPLACE)==0){
  s->published++;
  if(fsync(s->dir)!=0)s->publication_uncertain=TRUE;
  g_print("HSREADY1 SNAPSHOT %u uncertain=%u\n",s->published,s->publication_uncertain);fflush(stdout);
 }
}
int main(int argc,char**argv){
 /* Fixed guest parent; single basename. No path supplied by request. */
 if(argc!=5||!fixedhex(argv[1],32)||!fixedhex(argv[2],32)||!fixedhex(argv[3],64))return 70;
 int parent=open("/run/ldoa-handshake",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
 if(parent<0)return 71;
 int dir=openat(parent,argv[1],O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);close(parent);
 if(dir<0)return 71;
 struct stat st;
 if(fstat(dir,&st)||(st.st_mode&0777)!=0700){close(dir);return 72;}
 const char*mode=argv[4];
 if(strcmp(mode,"normal")!=0&&strcmp(mode,"monitor_disabled")!=0){close(dir);return 75;}
 State s={.dir=dir,.token=argv[2],.pin=argv[3]};
 int session=open_regular(&s,"session",O_RDONLY);
 if(session<0){close(dir);return 74;}
 char actual[256];ssize_t bytes=read(session,actual,sizeof(actual));close(session);
 char *wanted=g_strdup_printf("HSSESSION1 %s %s %s\n",argv[1],argv[2],argv[3]);
 gboolean matched=bytes==(ssize_t)strlen(wanted)&&memcmp(actual,wanted,bytes)==0;g_free(wanted);
 if(!matched){close(dir);return 74;}
 s.loop=g_main_loop_new(NULL,FALSE);s.deadline=g_get_monotonic_time()+3500000;
 if(strcmp(mode,"monitor_disabled")==0){g_print("HSREADY1 UNAVAILABLE MONITOR\n");fflush(stdout);g_main_loop_unref(s.loop);close(dir);return 73;}
 /* Backend must be inotify. No polling monitor or fallback. */
 char*path=g_strdup_printf("/run/ldoa-handshake/%s",argv[1]);GFile*f=g_file_new_for_path(path);g_free(path);GError*err=NULL;GFileMonitor*m=g_file_monitor_directory(f,G_FILE_MONITOR_NONE,NULL,&err);g_object_unref(f);
 if(!m||strcmp(G_OBJECT_TYPE_NAME(m),"GInotifyFileMonitor")!=0){if(m)g_object_unref(m);
 if(err)g_error_free(err);
 g_main_loop_unref(s.loop);close(dir);return 73;}
 g_signal_connect(m,"changed",G_CALLBACK(changed),&s);g_timeout_add(3500,expire,&s);
 g_print("HSREADY1 READY uid=%lu euid=%lu owner=%lu mode=%u backend=INOTIFY\n",(unsigned long)getuid(),(unsigned long)geteuid(),(unsigned long)st.st_uid,st.st_mode&0777);fflush(stdout);
 g_main_loop_run(s.loop);g_file_monitor_cancel(m);g_object_unref(m);g_main_loop_unref(s.loop);close(dir);return 0;
}
