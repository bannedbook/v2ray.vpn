git config --global core.autocrlf true
git pull origin master
git add -A
git commit -m "update to v7.2.1"
git push origin master
git tag -a v7.2.1 -m "v7.2.1"
git push origin --tags
pause